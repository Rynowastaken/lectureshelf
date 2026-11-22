use chrono::{Datelike, NaiveDateTime, NaiveTime, Weekday};
use reqwest::{header::ACCEPT, StatusCode, Url};
use serde::{Deserialize, Serialize};
use std::{sync::OnceLock, time::Duration};

const EPORTAL_SCHEDULE_PATH: &str = "/api/class-schedule";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CourseSlot {
    pub name: String,
    #[serde(default)]
    pub teacher: String,
    #[serde(default)]
    pub room: String,
    #[serde(default)]
    pub color: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TimeSlotRow {
    pub slot: String,
    pub time: String,
    /// 7 entries: Monday (0) .. Sunday (6)
    pub days: Vec<Option<CourseSlot>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UnifiedSchedule {
    pub school_name: String,
    pub source_url: String,
    pub synced_at: i64,
    pub slots: Vec<TimeSlotRow>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MatchKind {
    Active,
    Upcoming,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CurrentClassMatch {
    pub kind: MatchKind,
    pub slot: String,
    pub time: String,
    pub weekday_index: usize,
    pub course: CourseSlot,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct KewutongResponse {
    #[serde(default)]
    status: String,
    #[serde(default)]
    school_name: String,
    #[serde(default)]
    data: Vec<RawSlotRow>,
}

#[derive(Debug, Deserialize)]
struct EportalWrappedResponse {
    #[serde(default, alias = "schoolName", alias = "school_name")]
    school_name: String,
    #[serde(default)]
    slots: Vec<RawSlotRow>,
    #[serde(default)]
    data: Vec<RawSlotRow>,
}

#[derive(Debug, Deserialize)]
struct AisScheduleResponse {
    periods: Vec<RawSlotRow>,
    #[serde(default)]
    stale: bool,
}

#[derive(Debug, Deserialize)]
struct RawSlotRow {
    slot: serde_json::Value,
    time: String,
    #[serde(default)]
    days: Vec<serde_json::Value>,
}

/// Resolves a user-entered schedule source (uni-campus-app share URL, raw share code,
/// or Personal-NUTC-Eportal JSON endpoint) into a fetchable JSON URL.
pub fn resolve_schedule_source_url(input: &str) -> Result<String, String> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err("Schedule source URL cannot be empty".to_string());
    }

    // Direct share code like "qVaaFS5A"
    if !trimmed.contains("://") && !trimmed.contains('/') {
        if trimmed
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        {
            return Ok(format!(
                "https://kewutong.cloud-service.win/api/v2/share/{trimmed}"
            ));
        }
        return Err(format!("Invalid schedule source or share code: {trimmed}"));
    }

    // uni-campus-app share URL: https://uni-campus-app.pages.dev/s/<code>
    if let Some(pos) = trimmed.find("uni-campus-app.pages.dev/s/") {
        let rest = &trimmed[pos + "uni-campus-app.pages.dev/s/".len()..];
        let code = rest.split(['?', '#', '/']).next().unwrap_or("").trim();
        if code.is_empty() {
            return Err("Missing share code in uni-campus-app URL".to_string());
        }
        return Ok(format!(
            "https://kewutong.cloud-service.win/api/v2/share/{code}"
        ));
    }

    if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        return Ok(trimmed.to_string());
    }

    Err(format!("Unsupported schedule source URL: {trimmed}"))
}

fn parse_raw_rows(rows: Vec<RawSlotRow>) -> Vec<TimeSlotRow> {
    rows.into_iter()
        .map(|row| {
            let slot_str = match row.slot {
                serde_json::Value::String(s) => s,
                serde_json::Value::Number(n) => n.to_string(),
                other => other.to_string(),
            };
            let mut days = Vec::with_capacity(7);
            for d in row.days.into_iter().take(7) {
                let parsed = if d.is_object() {
                    let name = d
                        .get("name")
                        .or_else(|| d.get("courseName"))
                        .and_then(|v| v.as_str())
                        .map(str::trim)
                        .unwrap_or("");
                    if name.is_empty() {
                        None
                    } else {
                        Some(CourseSlot {
                            name: name.to_string(),
                            teacher: d
                                .get("teacher")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .trim()
                                .to_string(),
                            room: d
                                .get("room")
                                .or_else(|| d.get("location"))
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .trim()
                                .to_string(),
                            color: d
                                .get("color")
                                .and_then(|v| v.as_str())
                                .unwrap_or("#6750A4")
                                .trim()
                                .to_string(),
                        })
                    }
                } else {
                    None
                };
                days.push(parsed);
            }
            while days.len() < 7 {
                days.push(None);
            }
            TimeSlotRow {
                slot: slot_str,
                time: row.time.trim().to_string(),
                days,
            }
        })
        .collect()
}

/// Parses either kewutong/uni-campus-app JSON or Personal-NUTC-Eportal JSON into UnifiedSchedule.
pub fn parse_schedule_json(raw: &str, source_url: &str) -> Result<UnifiedSchedule, String> {
    let raw = raw.trim_start_matches('\u{feff}');
    let now = chrono::Utc::now().timestamp();

    if let Ok(ais) = serde_json::from_str::<AisScheduleResponse>(raw) {
        if ais.stale {
            return Err("NUTC ePortal could only return a stale timetable. Refresh it in the portal and try again.".into());
        }
        if ais.periods.is_empty()
            || ais
                .periods
                .iter()
                .any(|row| row.days.len() != 7 || parse_time_range(&row.time).is_none())
        {
            return Err("NUTC ePortal returned invalid timetable periods".into());
        }
        return Ok(UnifiedSchedule {
            school_name: "國立臺中科技大學".into(),
            source_url: source_url.into(),
            synced_at: now,
            slots: parse_raw_rows(ais.periods),
        });
    }

    if let Ok(kw) = serde_json::from_str::<KewutongResponse>(raw) {
        if !kw.data.is_empty() || kw.status == "ok" {
            return Ok(UnifiedSchedule {
                school_name: if kw.school_name.is_empty() {
                    "國立臺中科技大學".to_string()
                } else {
                    kw.school_name
                },
                source_url: source_url.to_string(),
                synced_at: now,
                slots: parse_raw_rows(kw.data),
            });
        }
    }

    if let Ok(ep) = serde_json::from_str::<EportalWrappedResponse>(raw) {
        let rows = if !ep.slots.is_empty() {
            ep.slots
        } else {
            ep.data
        };
        if !rows.is_empty() {
            return Ok(UnifiedSchedule {
                school_name: if ep.school_name.is_empty() {
                    "NUTC Eportal".to_string()
                } else {
                    ep.school_name
                },
                source_url: source_url.to_string(),
                synced_at: now,
                slots: parse_raw_rows(rows),
            });
        }
    }

    if let Ok(rows) = serde_json::from_str::<Vec<RawSlotRow>>(raw) {
        if !rows.is_empty() {
            return Ok(UnifiedSchedule {
                school_name: "NUTC Eportal".to_string(),
                source_url: source_url.to_string(),
                synced_at: now,
                slots: parse_raw_rows(rows),
            });
        }
    }

    Err("Unrecognized schedule JSON format".to_string())
}

fn parse_time_range(range: &str) -> Option<(NaiveTime, NaiveTime)> {
    let normalized = range.replace(['～', '-', '–', '—'], "~");
    let mut parts = normalized.split('~');
    let start_str = parts.next()?.trim();
    let end_str = parts.next()?.trim();
    let start = NaiveTime::parse_from_str(start_str, "%H:%M").ok()?;
    let end = NaiveTime::parse_from_str(end_str, "%H:%M").ok()?;
    Some((start, end))
}

fn weekday_to_index(wd: Weekday) -> usize {
    match wd {
        Weekday::Mon => 0,
        Weekday::Tue => 1,
        Weekday::Wed => 2,
        Weekday::Thu => 3,
        Weekday::Fri => 4,
        Weekday::Sat => 5,
        Weekday::Sun => 6,
    }
}

/// Finds the currently active class or an upcoming class starting within `grace_minutes`.
pub fn match_current_class(
    schedule: &UnifiedSchedule,
    local_dt: NaiveDateTime,
    grace_minutes: i64,
) -> Option<CurrentClassMatch> {
    let day_idx = weekday_to_index(local_dt.weekday());
    let now_time = local_dt.time();

    // Pass 1: exact active class
    for row in &schedule.slots {
        if let Some(Some(course)) = row.days.get(day_idx) {
            if let Some((start, end)) = parse_time_range(&row.time) {
                if now_time >= start && now_time <= end {
                    return Some(CurrentClassMatch {
                        kind: MatchKind::Active,
                        slot: row.slot.clone(),
                        time: row.time.clone(),
                        weekday_index: day_idx,
                        course: course.clone(),
                    });
                }
            }
        }
    }

    // Pass 2: upcoming class within grace_minutes
    let mut best_upcoming: Option<(i64, CurrentClassMatch)> = None;
    for row in &schedule.slots {
        if let Some(Some(course)) = row.days.get(day_idx) {
            if let Some((start, _end)) = parse_time_range(&row.time) {
                let diff_mins = (start - now_time).num_minutes();
                if diff_mins > 0 && diff_mins <= grace_minutes {
                    let candidate = CurrentClassMatch {
                        kind: MatchKind::Upcoming,
                        slot: row.slot.clone(),
                        time: row.time.clone(),
                        weekday_index: day_idx,
                        course: course.clone(),
                    };
                    match &best_upcoming {
                        Some((best_diff, _)) if diff_mins >= *best_diff => {}
                        _ => best_upcoming = Some((diff_mins, candidate)),
                    }
                }
            }
        }
    }

    best_upcoming.map(|(_, m)| m)
}

pub async fn fetch_schedule(source_input: &str) -> Result<UnifiedSchedule, String> {
    static HTTP_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    let resolved = resolve_schedule_source_url(source_input)?;
    let url = Url::parse(&resolved).map_err(|_| "Invalid schedule source URL".to_string())?;
    let client = HTTP_CLIENT.get_or_init(reqwest::Client::new);
    let result = fetch_schedule_at(client, &url).await;

    // A root URL may already serve schedule JSON. If it does not, try the Portal API.
    if result.is_err() && url.path() == "/" {
        let endpoint = url.join(EPORTAL_SCHEDULE_PATH).expect("valid portal path");
        return fetch_schedule_at(client, &endpoint).await;
    }
    result
}

async fn fetch_schedule_at(client: &reqwest::Client, url: &Url) -> Result<UnifiedSchedule, String> {
    let is_eportal = url.path() == EPORTAL_SCHEDULE_PATH;
    let resp = client
        .get(url.clone())
        .header(ACCEPT, "application/json")
        // A cold portal request may open a browser and sign in to AIS on the server.
        .timeout(Duration::from_secs(if is_eportal { 120 } else { 15 }))
        .send()
        .await
        .map_err(|e| format!("Failed to fetch schedule: {}", e.without_url()))?;
    let status = resp.status();
    let requires_sign_in =
        is_eportal && matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN);
    if !status.is_success() && !requires_sign_in {
        return Err(format!("Schedule server returned HTTP {status}"));
    }

    let body = resp
        .text()
        .await
        .map_err(|e| format!("Failed to read schedule response: {}", e.without_url()))?;

    if requires_sign_in {
        if serde_json::from_str::<serde_json::Value>(&body).is_ok_and(|error| {
            error.get("code").and_then(|code| code.as_str()) == Some("EPORTAL_LOGIN_REQUIRED")
        }) {
            return Err("Sign in to ePortal on the NUTC Portal dashboard, then sync again.".into());
        }
        return Err("This NUTC Portal requires browser access. Open it to sign in, then import its timetable JSON.".into());
    }
    if is_eportal && body.trim_start().starts_with('<') {
        return Err("NUTC Portal returned a web page. If it requires browser access, sign in there and import its timetable JSON.".into());
    }

    parse_schedule_json(&body, url.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{http::StatusCode, routing::get, Router};
    use chrono::NaiveDate;

    // Shaped like Personal-NUTC-Eportal's /api/class-schedule response.
    const AIS_SCHEDULE: &str = r#"{
        "source":"NUTC AIS", "sourceUrl":"https://ais.nutc.edu.tw/student/courses/my_week_time.aspx",
        "timezone":"Asia/Taipei", "fetchedAt":"2026-11-17T12:00:00.000Z", "termLabel":"115年(上)",
        "dayLabels":["週一","週二","週三","週四","週五","週六","週日"],
        "periods":[{"slot":"11","time":"19:10~20:00","days":[{},{"name":"演算法","teacher":"王老師","room":"3402"},{},{},{},{},{"name":"體育","teacher":"李老師","room":"體育館"}]}],
        "courses":[], "scheduledCells":2, "cached":false, "stale":false, "calendar":{"days":[]}
    }"#;

    #[tokio::test]
    async fn fetches_the_ais_schedule_from_a_dashboard_url_and_keeps_its_endpoint() {
        let app = Router::new()
            .route("/", get(|| async { "<html>NUTC Portal</html>" }))
            .route("/api/class-schedule", get(|| async { AIS_SCHEDULE }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let parsed = fetch_schedule(&origin).await.unwrap();
        assert_eq!(parsed.source_url, format!("{origin}/api/class-schedule"));
        assert_eq!(parsed.school_name, "國立臺中科技大學");
        let row = &parsed.slots[0];
        assert_eq!(row.slot, "11");
        assert_eq!(row.time, "19:10~20:00");
        assert!(row.days[0].is_none());
        assert_eq!(row.days[6].as_ref().unwrap().name, "體育");

        let tuesday = NaiveDate::from_ymd_opt(2026, 11, 17)
            .unwrap()
            .and_hms_opt(19, 30, 0)
            .unwrap();
        let matched = match_current_class(&parsed, tuesday, 15).unwrap();
        assert_eq!(matched.kind, MatchKind::Active);
        assert_eq!(matched.course.name, "演算法");
        assert_eq!(matched.course.teacher, "王老師");
        assert_eq!(matched.course.room, "3402");
        server.abort();
    }

    #[tokio::test]
    async fn a_protected_portal_endpoint_explains_how_to_import() {
        let app = Router::new().route(
            "/api/class-schedule",
            get(|| async { (StatusCode::FORBIDDEN, "Authentication required") }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!(
            "http://{}/api/class-schedule",
            listener.local_addr().unwrap()
        );
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let error = fetch_schedule(&endpoint).await.unwrap_err();
        assert!(error.contains("sign in"), "{error}");
        assert!(error.contains("JSON"), "{error}");
        server.abort();
    }

    #[test]
    fn imports_ais_json_without_using_the_private_ais_url_for_refresh() {
        let parsed = parse_schedule_json(AIS_SCHEDULE, "").unwrap();
        assert_eq!(parsed.source_url, "");
        assert_eq!(parsed.slots[0].days[1].as_ref().unwrap().name, "演算法");
    }

    #[test]
    fn rejects_an_incomplete_or_stale_ais_timetable() {
        assert!(parse_schedule_json(r#"{"periods":[]}"#, "").is_err());
        assert!(parse_schedule_json(
            r#"{"periods":[{"slot":"1","time":"08:10~09:00","days":[{}]}]}"#,
            ""
        )
        .is_err());
        assert!(parse_schedule_json(
            &AIS_SCHEDULE.replace("\"stale\":false", "\"stale\":true"),
            ""
        )
        .unwrap_err()
        .contains("stale"));
    }

    #[test]
    fn test_resolve_uni_campus_share_url() {
        assert_eq!(
            resolve_schedule_source_url("https://uni-campus-app.pages.dev/s/qVaaFS5A").unwrap(),
            "https://kewutong.cloud-service.win/api/v2/share/qVaaFS5A"
        );
        assert_eq!(
            resolve_schedule_source_url("qVaaFS5A").unwrap(),
            "https://kewutong.cloud-service.win/api/v2/share/qVaaFS5A"
        );
        assert_eq!(
            resolve_schedule_source_url("https://my-eportal.example.com/api/schedule").unwrap(),
            "https://my-eportal.example.com/api/schedule"
        );
        assert!(resolve_schedule_source_url("   ").is_err());
    }

    #[test]
    fn test_parse_kewutong_schedule() {
        let raw = r##"{
            "status":"ok",
            "nickname":"",
            "schoolName":"國立臺中科技大學",
            "expiresAt":1802314774943,
            "data":[
                {"slot":"1","time":"08:10~09:00","days":[{},{"name":"體育","teacher":"李俊杰","room":"0000","color":"#FFD600"},{"name":"大學之道","teacher":"何昕家","room":"3309","color":"#00DAC3"},{},{},{},{}]}
            ]
        }"##;
        let parsed = parse_schedule_json(
            raw,
            "https://kewutong.cloud-service.win/api/v2/share/qVaaFS5A",
        )
        .unwrap();
        assert_eq!(parsed.school_name, "國立臺中科技大學");
        assert_eq!(parsed.slots.len(), 1);
        assert!(parsed.slots[0].days[0].is_none());
        let tue = parsed.slots[0].days[1].as_ref().unwrap();
        assert_eq!(tue.name, "體育");
        assert_eq!(tue.teacher, "李俊杰");
        assert_eq!(tue.room, "0000");
    }

    #[test]
    fn test_parse_eportal_schedule() {
        let raw = r##"{
            "schoolName": "NUTC Eportal",
            "slots": [
                {"slot":"5","time":"13:25~14:15","days":[{"name":"演算法","teacher":"王老師","room":"3402"},{},{},{},{},{},{}]}
            ]
        }"##;
        let parsed = parse_schedule_json(raw, "https://eportal.local/api/schedule").unwrap();
        assert_eq!(parsed.school_name, "NUTC Eportal");
        assert_eq!(parsed.slots[0].days[0].as_ref().unwrap().name, "演算法");
    }

    #[test]
    fn test_match_current_class_active_and_grace() {
        let raw = r##"{
            "status":"ok",
            "schoolName":"國立臺中科技大學",
            "data":[
                {"slot":"1","time":"08:10~09:00","days":[{},{"name":"體育","teacher":"李俊杰","room":"0000","color":"#FFD600"},{},{},{},{},{}]}
            ]
        }"##;
        let sched = parse_schedule_json(raw, "test").unwrap();

        // 2026-11-10 is Tuesday (index 1)
        let active_dt = NaiveDate::from_ymd_opt(2026, 11, 10)
            .unwrap()
            .and_hms_opt(8, 30, 0)
            .unwrap();
        let m1 = match_current_class(&sched, active_dt, 15).unwrap();
        assert_eq!(m1.kind, MatchKind::Active);
        assert_eq!(m1.course.name, "體育");

        let upcoming_dt = NaiveDate::from_ymd_opt(2026, 11, 10)
            .unwrap()
            .and_hms_opt(7, 58, 0)
            .unwrap();
        let m2 = match_current_class(&sched, upcoming_dt, 15).unwrap();
        assert_eq!(m2.kind, MatchKind::Upcoming);
        assert_eq!(m2.course.name, "體育");

        let none_dt = NaiveDate::from_ymd_opt(2026, 11, 15)
            .unwrap()
            .and_hms_opt(23, 0, 0)
            .unwrap();
        assert!(match_current_class(&sched, none_dt, 15).is_none());
    }
}
