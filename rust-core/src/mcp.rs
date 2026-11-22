use std::convert::Infallible;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::extract::{Path, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::Local;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::{oneshot, Mutex};
use tokio_stream::wrappers::IntervalStream;
use tokio_stream::StreamExt;
use tower_http::cors::CorsLayer;

use crate::schedule::{fetch_schedule, match_current_class};
use crate::storage::{RecordingRecord, Storage};

pub trait RecordingController: Send + Sync {
    fn start_recording(&self, course_json: &str) -> Result<RecordingRecord, String>;
    fn stop_recording(&self) -> Result<RecordingRecord, String>;
}

pub struct McpState {
    pub storage: Arc<Storage>,
    pub controller: Arc<dyn RecordingController>,
    pub auth_token: Option<String>,
    pub request_log: Mutex<Vec<String>>,
}

impl McpState {
    async fn log_call(&self, entry: String) {
        let mut log = self.request_log.lock().await;
        log.push(entry);
        if log.len() > 50 {
            let excess = log.len() - 50;
            log.drain(0..excess);
        }
    }
}

pub struct McpServerHandle {
    pub port: u16,
    shutdown_tx: Option<oneshot::Sender<()>>,
}

impl McpServerHandle {
    pub async fn start(
        port: u16,
        auth_token: Option<String>,
        storage: Arc<Storage>,
        controller: Arc<dyn RecordingController>,
    ) -> Result<(Self, Arc<McpState>), String> {
        let state = Arc::new(McpState {
            storage,
            controller,
            auth_token: auth_token.filter(|s| !s.trim().is_empty()),
            request_log: Mutex::new(Vec::new()),
        });
        let router = build_router(state.clone());
        let addr = SocketAddr::from(([0, 0, 0, 0], port));
        let listener = tokio::net::TcpListener::bind(addr)
            .await
            .map_err(|e| format!("Failed to bind MCP server on port {port}: {e}"))?;
        let actual_port = listener
            .local_addr()
            .map(|a| a.port())
            .unwrap_or(port);

        let (tx, rx) = oneshot::channel::<()>();
        tokio::spawn(async move {
            let _ = axum::serve(listener, router)
                .with_graceful_shutdown(async {
                    let _ = rx.await;
                })
                .await;
        });

        Ok((
            Self {
                port: actual_port,
                shutdown_tx: Some(tx),
            },
            state,
        ))
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
    }
}

impl Drop for McpServerHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

pub fn build_router(state: Arc<McpState>) -> Router {
    Router::new()
        .route("/health", get(health_handler))
        .route("/mcp", post(mcp_post_handler).get(mcp_sse_handler))
        .route("/files/:id", get(file_download_handler))
        .layer(CorsLayer::permissive())
        .with_state(state)
}

fn check_auth(headers: &HeaderMap, expected: &Option<String>) -> Result<(), Response> {
    let Some(token) = expected else {
        return Ok(());
    };
    let provided = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::trim);
    if provided == Some(token.as_str()) {
        Ok(())
    } else {
        Err((
            StatusCode::UNAUTHORIZED,
            Json(json!({"error": "Unauthorized: invalid or missing Bearer token"})),
        )
            .into_response())
    }
}

async fn health_handler(State(state): State<Arc<McpState>>) -> Json<Value> {
    let has_schedule = state.storage.load_schedule().ok().flatten().is_some();
    let count = state
        .storage
        .list_recordings(None, 1000)
        .map(|v| v.len())
        .unwrap_or(0);
    Json(json!({
        "status": "ok",
        "server": "lecture-shelf-mcp",
        "version": env!("CARGO_PKG_VERSION"),
        "hasSchedule": has_schedule,
        "recordingCount": count
    }))
}

async fn mcp_sse_handler(
    headers: HeaderMap,
    State(state): State<Arc<McpState>>,
) -> Result<Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>>, Response> {
    check_auth(&headers, &state.auth_token)?;
    let stream = IntervalStream::new(tokio::time::interval(Duration::from_secs(15))).map(|_| {
        Ok(Event::default()
            .event("ping")
            .data(r#"{"status":"alive"}"#))
    });
    Ok(Sse::new(stream).keep_alive(KeepAlive::default()))
}

async fn file_download_handler(
    headers: HeaderMap,
    Path(id): Path<String>,
    State(state): State<Arc<McpState>>,
) -> Result<Response, Response> {
    check_auth(&headers, &state.auth_token)?;
    let rec = state
        .storage
        .get_recording(&id)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e).into_response())?
        .ok_or_else(|| (StatusCode::NOT_FOUND, "Recording not found").into_response())?;

    let bytes = tokio::fs::read(&rec.file_path)
        .await
        .map_err(|e| (StatusCode::NOT_FOUND, format!("Audio file missing: {e}")).into_response())?;

    Ok(Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/mp4")
        .header(
            header::CONTENT_DISPOSITION,
            format!("inline; filename=\"{}.m4a\"", rec.id),
        )
        .body(Body::from(bytes))
        .unwrap())
}

#[derive(Debug, Deserialize)]
struct JsonRpcRequest {
    #[allow(dead_code)]
    jsonrpc: Option<String>,
    id: Option<Value>,
    method: String,
    #[serde(default)]
    params: Value,
}

#[derive(Debug, Serialize)]
struct JsonRpcResponse {
    jsonrpc: &'static str,
    id: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<Value>,
}

fn ok_resp(id: Value, result: Value) -> Json<JsonRpcResponse> {
    Json(JsonRpcResponse {
        jsonrpc: "2.0",
        id,
        result: Some(result),
        error: None,
    })
}

fn err_resp(id: Value, code: i32, message: impl Into<String>) -> Json<JsonRpcResponse> {
    Json(JsonRpcResponse {
        jsonrpc: "2.0",
        id,
        result: None,
        error: Some(json!({ "code": code, "message": message.into() })),
    })
}

fn tool_ok(payload: Value) -> Value {
    let text = serde_json::to_string_pretty(&payload).unwrap_or_else(|_| payload.to_string());
    json!({
        "content": [{ "type": "text", "text": text }],
        "structuredContent": payload,
        "isError": false
    })
}

fn tool_err(message: impl Into<String>) -> Value {
    json!({
        "content": [{ "type": "text", "text": message.into() }],
        "isError": true
    })
}

async fn mcp_post_handler(
    headers: HeaderMap,
    State(state): State<Arc<McpState>>,
    Json(req): Json<JsonRpcRequest>,
) -> Result<Response, Response> {
    check_auth(&headers, &state.auth_token)?;
    let id = req.id.clone().unwrap_or(Value::Null);
    state
        .log_call(format!(
            "{} {}",
            Local::now().format("%H:%M:%S"),
            req.method
        ))
        .await;

    if req.method == "notifications/initialized" {
        return Ok(StatusCode::ACCEPTED.into_response());
    }

    let resp = match req.method.as_str() {
        "initialize" => ok_resp(
            id,
            json!({
                "protocolVersion": "2024-11-05",
                "capabilities": {
                    "tools": { "listChanged": false },
                    "resources": { "subscribe": false, "listChanged": false }
                },
                "serverInfo": {
                    "name": "lecture-shelf-mcp",
                    "version": env!("CARGO_PKG_VERSION")
                }
            }),
        ),
        "ping" => ok_resp(id, json!({})),
        "tools/list" => ok_resp(id, json!({ "tools": tool_definitions() })),
        "tools/call" => {
            let name = req
                .params
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let args = req
                .params
                .get("arguments")
                .cloned()
                .unwrap_or_else(|| json!({}));
            let res = dispatch_tool(&state, name, args).await;
            ok_resp(id, res)
        }
        "resources/list" => ok_resp(
            id,
            json!({
                "resources": [
                    {
                        "uri": "schedule://weekly",
                        "name": "Weekly Class Schedule",
                        "mimeType": "application/json"
                    },
                    {
                        "uri": "recordings://index",
                        "name": "Class Recordings Index",
                        "mimeType": "application/json"
                    }
                ]
            }),
        ),
        "resources/read" => {
            let uri = req
                .params
                .get("uri")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            match uri {
                "schedule://weekly" => match state.storage.load_schedule() {
                    Ok(sched) => ok_resp(
                        id,
                        json!({
                            "contents": [{
                                "uri": uri,
                                "mimeType": "application/json",
                                "text": serde_json::to_string_pretty(&sched).unwrap_or_default()
                            }]
                        }),
                    ),
                    Err(e) => err_resp(id, -32000, e),
                },
                "recordings://index" => match state.storage.list_recordings(None, 200) {
                    Ok(recs) => ok_resp(
                        id,
                        json!({
                            "contents": [{
                                "uri": uri,
                                "mimeType": "application/json",
                                "text": serde_json::to_string_pretty(&recs).unwrap_or_default()
                            }]
                        }),
                    ),
                    Err(e) => err_resp(id, -32000, e),
                },
                _ => err_resp(id, -32602, format!("Unknown resource URI: {uri}")),
            }
        }
        other => err_resp(id, -32601, format!("Method not found: {other}")),
    };

    Ok(resp.into_response())
}

async fn dispatch_tool(state: &Arc<McpState>, name: &str, args: Value) -> Value {
    match name {
        "get_schedule" => match state.storage.load_schedule() {
            Ok(Some(sched)) => tool_ok(serde_json::to_value(sched).unwrap_or(Value::Null)),
            Ok(None) => tool_err("No schedule synced yet. Call sync_schedule first."),
            Err(e) => tool_err(e),
        },
        "get_current_class" => match state.storage.load_schedule() {
            Ok(Some(sched)) => {
                let now = Local::now().naive_local();
                let matched = match_current_class(&sched, now, 15);
                tool_ok(json!({ "currentClass": matched }))
            }
            Ok(None) => tool_ok(json!({ "currentClass": null })),
            Err(e) => tool_err(e),
        },
        "list_recordings" => {
            let course = args.get("course_name").and_then(|v| v.as_str());
            let limit = args
                .get("limit")
                .and_then(|v| v.as_u64())
                .unwrap_or(50) as usize;
            match state.storage.list_recordings(course, limit) {
                Ok(recs) => tool_ok(json!({ "recordings": recs })),
                Err(e) => tool_err(e),
            }
        }
        "get_recording_details" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            if id.is_empty() {
                return tool_err("Missing required parameter: id");
            }
            match state.storage.get_recording(id) {
                Ok(Some(rec)) => tool_ok(json!({
                    "recording": rec,
                    "downloadPath": format!("/files/{}", rec.id)
                })),
                Ok(None) => tool_err(format!("Recording not found: {id}")),
                Err(e) => tool_err(e),
            }
        }
        "update_recording_notes" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            if id.is_empty() {
                return tool_err("Missing required parameter: id");
            }
            let notes = args.get("notes").and_then(|v| v.as_str());
            let tags = args.get("tags").and_then(|v| {
                v.as_array().map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_str().map(str::to_string))
                        .collect::<Vec<_>>()
                })
            });
            match state.storage.update_recording_notes(id, notes, tags) {
                Ok(rec) => tool_ok(json!({ "recording": rec })),
                Err(e) => tool_err(e),
            }
        }
        "start_recording" => {
            let course_json = serde_json::to_string(&args).unwrap_or_else(|_| "{}".to_string());
            let controller = state.controller.clone();
            match tokio::task::spawn_blocking(move || controller.start_recording(&course_json)).await {
                Ok(Ok(rec)) => tool_ok(json!({ "recording": rec })),
                Ok(Err(e)) => tool_err(e),
                Err(e) => tool_err(format!("Task join error: {e}")),
            }
        }
        "stop_recording" => {
            let controller = state.controller.clone();
            match tokio::task::spawn_blocking(move || controller.stop_recording()).await {
                Ok(Ok(rec)) => tool_ok(json!({ "recording": rec })),
                Ok(Err(e)) => tool_err(e),
                Err(e) => tool_err(format!("Task join error: {e}")),
            }
        }
        "sync_schedule" => {
            let url = match args.get("source_url").and_then(Value::as_str) {
                Some(url) => url.to_string(),
                None => match state.storage.load_schedule() {
                    Ok(Some(schedule)) if schedule.source_url.is_empty() => {
                        return tool_err("The current timetable was imported from a file. Provide a schedule URL to sync.");
                    }
                    Ok(Some(schedule)) => schedule.source_url,
                    Ok(None) => state.storage.get_setting("schedule_source_url").ok().flatten()
                        .unwrap_or_else(|| "https://uni-campus-app.pages.dev/s/qVaaFS5A".to_string()),
                    Err(error) => return tool_err(error),
                },
            };
            match fetch_schedule(&url).await {
                Ok(sched) => {
                    if let Err(e) = state.storage.save_schedule(&sched) {
                        return tool_err(e);
                    }
                    tool_ok(json!({ "schedule": sched }))
                }
                Err(e) => tool_err(e),
            }
        }
        other => tool_err(format!("Unknown tool: {other}")),
    }
}

fn tool_definitions() -> Value {
    json!([
        {
            "name": "get_schedule",
            "description": "Returns the synced NUTC weekly class schedule.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "get_current_class",
            "description": "Returns the currently active or upcoming (within 15 min) class based on device local time.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "list_recordings",
            "description": "Lists class recordings stored on the phone, optionally filtered by course_name.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "course_name": { "type": "string" },
                    "limit": { "type": "integer", "default": 50 }
                }
            }
        },
        {
            "name": "get_recording_details",
            "description": "Gets full metadata, notes, tags, and audio download path (/files/{id}) for a recording.",
            "inputSchema": {
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }
        },
        {
            "name": "update_recording_notes",
            "description": "Updates AI-generated notes or tags on a recording.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "id": { "type": "string" },
                    "notes": { "type": "string" },
                    "tags": { "type": "array", "items": { "type": "string" } }
                },
                "required": ["id"]
            }
        },
        {
            "name": "start_recording",
            "description": "Starts audio recording on the phone, auto-tagging the current class or using optional overrides.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "course_name": { "type": "string" },
                    "teacher": { "type": "string" },
                    "room": { "type": "string" },
                    "slot": { "type": "string" }
                }
            }
        },
        {
            "name": "stop_recording",
            "description": "Stops the active audio recording and saves its duration.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "sync_schedule",
            "description": "Fetches and caches the schedule from a uni-campus-app share link/code or a Personal-NUTC-Eportal dashboard or timetable endpoint.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "source_url": { "type": "string" }
                }
            }
        }
    ])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schedule::{TimeSlotRow, UnifiedSchedule};
    use axum::body::Body;
    use axum::http::Request;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    struct MockController {
        storage: Arc<Storage>,
    }

    impl RecordingController for MockController {
        fn start_recording(&self, course_json: &str) -> Result<RecordingRecord, String> {
            let v: Value = serde_json::from_str(course_json).unwrap_or_else(|_| json!({}));
            let course_name = v
                .get("course_name")
                .and_then(|x| x.as_str())
                .unwrap_or("Test Course")
                .to_string();
            let rec = RecordingRecord {
                id: "mock-1".to_string(),
                file_path: "/tmp/mock-1.m4a".to_string(),
                course_name,
                teacher: "Teacher".to_string(),
                room: "3309".to_string(),
                slot: "1".to_string(),
                started_at: 1700000000,
                duration_ms: 0,
                status: "recording".to_string(),
                notes: String::new(),
                tags: vec![],
            };
            self.storage.insert_recording(&rec)?;
            Ok(rec)
        }

        fn stop_recording(&self) -> Result<RecordingRecord, String> {
            self.storage.complete_recording("mock-1", 5000)
        }
    }

    fn test_state(auth_token: Option<String>) -> Arc<McpState> {
        let storage = Arc::new(Storage::open_in_memory().unwrap());
        storage
            .save_schedule(&UnifiedSchedule {
                school_name: "國立臺中科技大學".to_string(),
                source_url: "test".to_string(),
                synced_at: 1700000000,
                slots: vec![TimeSlotRow {
                    slot: "1".to_string(),
                    time: "08:10~09:00".to_string(),
                    days: vec![None; 7],
                }],
            })
            .unwrap();
        let controller = Arc::new(MockController {
            storage: storage.clone(),
        });
        Arc::new(McpState {
            storage,
            controller,
            auth_token,
            request_log: Mutex::new(Vec::new()),
        })
    }

    async fn post_mcp(app: &Router, body: Value, token: Option<&str>) -> (StatusCode, Value) {
        let mut builder = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("content-type", "application/json");
        if let Some(t) = token {
            builder = builder.header("authorization", format!("Bearer {t}"));
        }
        let req = builder
            .body(Body::from(serde_json::to_vec(&body).unwrap()))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        let status = resp.status();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let json: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        (status, json)
    }

    #[tokio::test]
    async fn test_mcp_initialize_and_tools_list() {
        let app = build_router(test_state(None));
        let (status, resp) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(resp["result"]["serverInfo"]["name"], "lecture-shelf-mcp");

        let (status, tools) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(tools["result"]["tools"].as_array().unwrap().len(), 8);
    }

    #[tokio::test]
    async fn test_mcp_schedule_and_recordings_tools() {
        let app = build_router(test_state(None));
        let (_, start) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"start_recording","arguments":{"course_name":"大學之道"}}}),
            None,
        )
        .await;
        assert_eq!(start["result"]["isError"], false);

        let (_, notes) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"update_recording_notes","arguments":{"id":"mock-1","notes":"Agent summary","tags":["ai"]}}}),
            None,
        )
        .await;
        assert_eq!(
            notes["result"]["structuredContent"]["recording"]["notes"],
            "Agent summary"
        );

        let (_, stop) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stop_recording","arguments":{}}}),
            None,
        )
        .await;
        assert_eq!(
            stop["result"]["structuredContent"]["recording"]["status"],
            "completed"
        );
    }

    #[tokio::test]
    async fn test_mcp_bearer_auth() {
        let app = build_router(test_state(Some("secret".to_string())));
        let (unauth, _) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":1,"method":"ping"}),
            None,
        )
        .await;
        assert_eq!(unauth, StatusCode::UNAUTHORIZED);

        let (ok, _) = post_mcp(
            &app,
            json!({"jsonrpc":"2.0","id":1,"method":"ping"}),
            Some("secret"),
        )
        .await;
        assert_eq!(ok, StatusCode::OK);
    }
}
