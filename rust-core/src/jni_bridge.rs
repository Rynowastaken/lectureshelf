use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use chrono::Local;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde_json::{json, Value};

use crate::mcp::{McpServerHandle, McpState, RecordingController};
use crate::schedule::{
    fetch_schedule, match_current_class, parse_schedule_json, CourseSlot, CurrentClassMatch,
    MatchKind, UnifiedSchedule,
};
use crate::storage::{build_recording_path_with_options, RecordingRecord, Storage};

struct CoreContext {
    runtime: tokio::runtime::Runtime,
    storage: Arc<Storage>,
    recordings_dir: PathBuf,
    jvm: JavaVM,
    bridge_ref: GlobalRef,
    mcp_handle: Mutex<Option<(McpServerHandle, Arc<McpState>)>>,
}

static CORE: OnceLock<CoreContext> = OnceLock::new();

struct JniRecordingController;

impl JniRecordingController {
    fn invoke_bridge(method: &str, arg: Option<&str>) -> Result<RecordingRecord, String> {
        let ctx = CORE.get().ok_or_else(|| "Core not initialized".to_string())?;
        let mut env = ctx
            .jvm
            .attach_current_thread()
            .map_err(|e| format!("Failed to attach JVM thread: {e}"))?;
        let res = match arg {
            Some(s) => {
                let j_arg = env
                    .new_string(s)
                    .map_err(|e| format!("JNI new_string failed: {e}"))?;
                env.call_method(
                    ctx.bridge_ref.as_obj(),
                    method,
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    &[JValue::Object(&j_arg)],
                )
            }
            None => env.call_method(
                ctx.bridge_ref.as_obj(),
                method,
                "()Ljava/lang/String;",
                &[],
            ),
        }
        .map_err(|e| format!("JNI {method} call failed: {e}"))?;

        let res_obj = res.l().map_err(|e| format!("JNI return type error: {e}"))?;
        let res_str: String = env
            .get_string(&JString::from(res_obj))
            .map_err(|e| format!("JNI get_string failed: {e}"))?
            .into();
        let val: Value = serde_json::from_str(&res_str)
            .map_err(|e| format!("Invalid JSON from RecordingBridge: {e}"))?;
        if let Some(err) = val.get("error").and_then(|v| v.as_str()) {
            return Err(err.to_string());
        }
        serde_json::from_value::<RecordingRecord>(val.get("recording").cloned().unwrap_or(val))
            .map_err(|e| format!("Failed to decode RecordingRecord: {e}"))
    }
}

impl RecordingController for JniRecordingController {
    fn start_recording(&self, course_json: &str) -> Result<RecordingRecord, String> {
        Self::invoke_bridge("startRecordingFromAgent", Some(course_json))
    }

    fn stop_recording(&self) -> Result<RecordingRecord, String> {
        Self::invoke_bridge("stopRecordingFromAgent", None)
    }
}

fn jstr(env: &mut JNIEnv, s: JString) -> String {
    env.get_string(&s).map(String::from).unwrap_or_default()
}

fn return_json(env: &mut JNIEnv, val: Value) -> jstring {
    let s = serde_json::to_string(&val)
        .unwrap_or_else(|_| r#"{"ok":false,"error":"serialize"}"#.to_string());
    env.new_string(s)
        .map(|js| js.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn ok_json(data: Value) -> Value {
    json!({ "ok": true, "data": data })
}

fn err_json(msg: impl Into<String>) -> Value {
    json!({ "ok": false, "error": msg.into() })
}

fn with_core(env: &mut JNIEnv, f: impl FnOnce(&CoreContext, &mut JNIEnv) -> Value) -> jstring {
    let val = match CORE.get() {
        Some(ctx) => f(ctx, env),
        None => err_json("Core not initialized"),
    };
    return_json(env, val)
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_initCore(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    recordings_dir: JString,
    bridge: JObject,
) -> jstring {
    if CORE.get().is_some() {
        return return_json(&mut env, ok_json(json!({ "alreadyInitialized": true })));
    }
    let files_path = PathBuf::from(jstr(&mut env, files_dir));
    let rec_path = PathBuf::from(jstr(&mut env, recordings_dir));
    if let Err(e) = std::fs::create_dir_all(&rec_path) {
        return return_json(&mut env, err_json(format!("Cannot create recordings dir: {e}")));
    }
    let db_path = files_path.join("class_recorder.db");
    let storage = match Storage::open(&db_path) {
        Ok(s) => Arc::new(s),
        Err(e) => return return_json(&mut env, err_json(e)),
    };
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => return return_json(&mut env, err_json(format!("Tokio runtime error: {e}"))),
    };
    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => return return_json(&mut env, err_json(format!("JavaVM error: {e}"))),
    };
    let bridge_ref = match env.new_global_ref(bridge) {
        Ok(r) => r,
        Err(e) => return return_json(&mut env, err_json(format!("GlobalRef error: {e}"))),
    };

    let _ = CORE.set(CoreContext {
        runtime,
        storage,
        recordings_dir: rec_path,
        jvm,
        bridge_ref,
        mcp_handle: Mutex::new(None),
    });

    return_json(&mut env, ok_json(json!({ "initialized": true })))
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_getDataVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    CORE.get()
        .map(|ctx| ctx.storage.data_version() as jlong)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_syncSchedule(
    mut env: JNIEnv,
    _class: JClass,
    source_url: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let url = jstr(env, source_url);
        save_schedule_result(&ctx.storage, ctx.runtime.block_on(fetch_schedule(&url)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_importScheduleJson(
    mut env: JNIEnv,
    _class: JClass,
    raw: JString,
    source_url: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let raw = jstr(env, raw);
        let source_url = jstr(env, source_url);
        save_schedule_result(&ctx.storage, parse_schedule_json(&raw, &source_url))
    })
}

fn save_schedule_result(storage: &Storage, result: Result<UnifiedSchedule, String>) -> Value {
    match result.and_then(|schedule| {
        storage.save_schedule(&schedule)?;
        Ok(schedule)
    }) {
        Ok(schedule) => ok_json(serde_json::to_value(schedule).unwrap_or(Value::Null)),
        Err(error) => err_json(error),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_getScheduleJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    with_core(&mut env, |ctx, _| match ctx.storage.load_schedule() {
        Ok(sched) => ok_json(serde_json::to_value(sched).unwrap_or(Value::Null)),
        Err(e) => err_json(e),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_getCurrentClassJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    with_core(&mut env, |ctx, _| match ctx.storage.load_schedule() {
        Ok(Some(sched)) => {
            let matched = match_current_class(&sched, Local::now().naive_local(), 15);
            ok_json(serde_json::to_value(matched).unwrap_or(Value::Null))
        }
        Ok(None) => ok_json(Value::Null),
        Err(e) => err_json(e),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_prepareRecording(
    mut env: JNIEnv,
    _class: JClass,
    course_override_json: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let raw_override = jstr(env, course_override_json);
        let override_val: Value = serde_json::from_str(&raw_override).unwrap_or(Value::Null);

        let now_local = Local::now().naive_local();
        let matched: Option<CurrentClassMatch> = if let Some(name) = override_val
            .get("courseName")
            .or_else(|| override_val.get("course_name"))
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            Some(CurrentClassMatch {
                kind: MatchKind::Active,
                slot: override_val
                    .get("slot")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                time: String::new(),
                weekday_index: 0,
                course: CourseSlot {
                    name: name.to_string(),
                    teacher: override_val
                        .get("teacher")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    room: override_val
                        .get("room")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    color: String::new(),
                },
            })
        } else {
            ctx.storage
                .load_schedule()
                .ok()
                .flatten()
                .and_then(|s| match_current_class(&s, now_local, 15))
        };

        let course_name = matched
            .as_ref()
            .map(|m| m.course.name.clone())
            .unwrap_or_else(|| "Uncategorized".to_string());
        let lesson_number = ctx.storage.count_course_recordings(&course_name).unwrap_or(0) + 1;
        let template = ctx.storage.get_setting("filename_template").ok().flatten();
        let date_format = ctx.storage.get_setting("date_format").ok().flatten();
        let path = build_recording_path_with_options(
            &ctx.recordings_dir,
            matched.as_ref(),
            now_local,
            template.as_deref(),
            date_format.as_deref(),
            lesson_number,
        );
        if let Some(parent) = path.parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                return err_json(format!("Failed to create course folder: {e}"));
            }
        }

        let rec = RecordingRecord {
            id: uuid::Uuid::new_v4().to_string(),
            file_path: path.to_string_lossy().to_string(),
            course_name,
            teacher: matched.as_ref().map(|m| m.course.teacher.clone()).unwrap_or_default(),
            room: matched.as_ref().map(|m| m.course.room.clone()).unwrap_or_default(),
            slot: matched.as_ref().map(|m| m.slot.clone()).unwrap_or_default(),
            started_at: chrono::Utc::now().timestamp(),
            duration_ms: 0,
            status: "recording".to_string(),
            notes: String::new(),
            tags: vec![],
        };

        match ctx.storage.insert_recording(&rec) {
            Ok(()) => ok_json(serde_json::to_value(rec).unwrap_or(Value::Null)),
            Err(e) => err_json(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_completeRecording(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
    duration_ms: jlong,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let rec_id = jstr(env, id);
        match ctx.storage.complete_recording(&rec_id, duration_ms) {
            Ok(rec) => ok_json(serde_json::to_value(rec).unwrap_or(Value::Null)),
            Err(e) => err_json(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_listRecordings(
    mut env: JNIEnv,
    _class: JClass,
    course_filter: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let filter = jstr(env, course_filter);
        let filter_opt = (!filter.trim().is_empty()).then_some(filter.as_str());
        match ctx.storage.list_recordings(filter_opt, 200) {
            Ok(list) => ok_json(serde_json::to_value(list).unwrap_or(Value::Null)),
            Err(e) => err_json(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_updateRecordingNotes(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
    notes: JString,
    tags_json: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let rec_id = jstr(env, id);
        let notes_str = jstr(env, notes);
        let tags_raw = jstr(env, tags_json);
        let tags: Option<Vec<String>> = (!tags_raw.trim().is_empty())
            .then(|| serde_json::from_str(&tags_raw).ok())
            .flatten();
        match ctx.storage.update_recording_notes(&rec_id, Some(&notes_str), tags) {
            Ok(rec) => ok_json(serde_json::to_value(rec).unwrap_or(Value::Null)),
            Err(e) => err_json(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_deleteRecording(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let rec_id = jstr(env, id);
        match ctx.storage.delete_recording(&rec_id) {
            Ok(()) => ok_json(json!({ "deleted": rec_id })),
            Err(e) => err_json(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_startMcpServer(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    auth_token: JString,
) -> jstring {
    with_core(&mut env, |ctx, env| {
        let token = jstr(env, auth_token);
        let mut guard = ctx.mcp_handle.lock().unwrap();
        if let Some((ref mut existing, _)) = *guard {
            existing.stop();
            *guard = None;
        }
        let controller = Arc::new(JniRecordingController);
        match ctx.runtime.block_on(McpServerHandle::start(
            port as u16,
            Some(token),
            ctx.storage.clone(),
            controller,
        )) {
            Ok((handle, state)) => {
                let actual_port = handle.port;
                *guard = Some((handle, state));
                ok_json(json!({ "running": true, "port": actual_port }))
            }
            Err(e) => err_json(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_stopMcpServer(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    with_core(&mut env, |ctx, _| {
        if let Some((mut handle, _)) = ctx.mcp_handle.lock().unwrap().take() {
            handle.stop();
        }
        ok_json(json!({ "running": false }))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_getMcpStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    with_core(&mut env, |ctx, _| {
        let guard = ctx.mcp_handle.lock().unwrap();
        match &*guard {
            Some((handle, state)) => {
                let logs = ctx
                    .runtime
                    .block_on(async { state.request_log.lock().await.clone() });
                ok_json(json!({
                    "running": true,
                    "port": handle.port,
                    "recentCalls": logs
                }))
            }
            None => ok_json(json!({
                "running": false,
                "port": 8765,
                "recentCalls": []
            })),
        }
    })
}

fn set_setting_jni(env: &mut JNIEnv, db_key: &str, json_field: &str, val: JString) -> jstring {
    with_core(env, |ctx, env| {
        let v = jstr(env, val);
        let trimmed = v.trim();
        match ctx.storage.set_setting(db_key, trimmed) {
            Ok(()) => ok_json(json!({ json_field: trimmed })),
            Err(e) => err_json(e),
        }
    })
}

fn get_setting_jni(env: &mut JNIEnv, db_key: &str, json_field: &str) -> jstring {
    with_core(env, |ctx, _| match ctx.storage.get_setting(db_key) {
        Ok(v) => ok_json(json!({ json_field: v.unwrap_or_default() })),
        Err(e) => err_json(e),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_setFilenameTemplate(
    mut env: JNIEnv,
    _class: JClass,
    template: JString,
) -> jstring {
    set_setting_jni(&mut env, "filename_template", "template", template)
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_getFilenameTemplate(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    get_setting_jni(&mut env, "filename_template", "template")
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_setDateFormat(
    mut env: JNIEnv,
    _class: JClass,
    date_format: JString,
) -> jstring {
    set_setting_jni(&mut env, "date_format", "dateFormat", date_format)
}

#[no_mangle]
pub extern "system" fn Java_com_ryno_classrecorder_core_RustCore_getDateFormat(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    get_setting_jni(&mut env, "date_format", "dateFormat")
}
