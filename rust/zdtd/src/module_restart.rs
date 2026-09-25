// Per-module hot restart.
//
// Saving a program's configuration used to require stopping the whole service
// and starting it again, which kills every other module's processes. This
// module restarts only the affected program: callers enqueue a task, a single
// background worker runs it, and a status file lets the app follow the progress
// (same queue pattern as runtime_apply).
//
// Only "operaproxy" is supported for now; other programs can be added by
// implementing stop/start halves in the match below.

use anyhow::Result;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    fs,
    path::Path,
    sync::{Mutex, OnceLock, atomic::{AtomicU64, Ordering}},
    thread,
    time::{SystemTime, UNIX_EPOCH},
};

use crate::{
    programs::operaproxy,
    shell,
};

const STATUS_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/module_restart/status.json";
const T2S_INSTANCES_DIR: &str = "/data/adb/modules/ZDT-D/api/t2s/instances";
const T2S_PORTS_DIR: &str = "/data/adb/modules/ZDT-D/api/t2s/ports";

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct ModuleRestartTask {
    program: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModuleRestartStatus {
    pub ok: bool,
    pub state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub program: Option<String>,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub updated_at_unix_ms: u64,
}

impl ModuleRestartStatus {
    fn idle() -> Self {
        Self {
            ok: true,
            state: "idle".to_string(),
            program: None,
            message: "Нет активного перезапуска модуля".to_string(),
            error: None,
            updated_at_unix_ms: now_ms(),
        }
    }

    fn for_task(program: &str, state: &str, message: String, error: Option<String>) -> Self {
        Self {
            ok: true,
            state: state.to_string(),
            program: Some(program.to_string()),
            message,
            error,
            updated_at_unix_ms: now_ms(),
        }
    }
}

#[derive(Debug)]
struct ModuleRestartQueue {
    status: ModuleRestartStatus,
    pending: std::collections::BTreeMap<String, ModuleRestartTask>,
    worker_running: bool,
}

impl Default for ModuleRestartQueue {
    fn default() -> Self {
        Self {
            status: ModuleRestartStatus::idle(),
            pending: std::collections::BTreeMap::new(),
            worker_running: false,
        }
    }
}

static QUEUE: OnceLock<Mutex<ModuleRestartQueue>> = OnceLock::new();
static GENERATION: AtomicU64 = AtomicU64::new(1);

fn queue() -> &'static Mutex<ModuleRestartQueue> {
    QUEUE.get_or_init(|| Mutex::new(ModuleRestartQueue::default()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis().min(u128::from(u64::MAX)) as u64)
        .unwrap_or(0)
}

fn write_status_file(status: &ModuleRestartStatus) {
    let path = Path::new(STATUS_FILE);
    if let Some(parent) = path.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            log::warn!("module_restart: failed to create status dir: {e:#}");
            return;
        }
    }
    match serde_json::to_string_pretty(status) {
        Ok(body) => {
            if let Err(e) = fs::write(path, body) {
                log::warn!("module_restart: failed to write status: {e:#}");
            }
        }
        Err(e) => log::warn!("module_restart: failed to serialize status: {e:#}"),
    }
}

fn set_status(status: ModuleRestartStatus) -> ModuleRestartStatus {
    if let Ok(mut guard) = queue().lock() {
        guard.status = status.clone();
    }
    write_status_file(&status);
    status
}

pub fn status() -> ModuleRestartStatus {
    queue()
        .lock()
        .map(|guard| guard.status.clone())
        .unwrap_or_else(|_| ModuleRestartStatus::idle())
}

pub fn status_json() -> serde_json::Value {
    serde_json::to_value(status()).unwrap_or_else(|_| json!({"ok": true, "state": "idle"}))
}

/// Drop everything pending and mark the queue idle. Called from the full
/// start/stop paths so a module restart can never resurrect processes the
/// global stop just killed.
pub fn clear() {
    GENERATION.fetch_add(1, Ordering::AcqRel);
    let status = ModuleRestartStatus::idle();
    if let Ok(mut guard) = queue().lock() {
        guard.pending.clear();
        guard.worker_running = false;
        guard.status = status.clone();
    }
    write_status_file(&status);
}

/// Enqueue a restart of a single module. When the service is not running the
/// request is a no-op: the saved config is picked up by the next full start.
pub fn schedule_restart(services_running: bool, program: &str) -> ModuleRestartStatus {
    let task = ModuleRestartTask { program: program.to_string() };

    if !services_running {
        return set_status(ModuleRestartStatus::for_task(
            program,
            "deferred_until_start",
            "Настройка сохранена и применится после запуска службы".to_string(),
            None,
        ));
    }

    let queued_status = ModuleRestartStatus::for_task(
        program,
        "queued",
        format!("Настройка сохранена, перезапуск модуля поставлен в очередь: {program}"),
        None,
    );

    let should_spawn = {
        let mut guard = match queue().lock() {
            Ok(guard) => guard,
            Err(_) => return queued_status,
        };
        guard.pending.insert(task.program.clone(), task);
        if guard.status.state != "running" {
            guard.status = queued_status.clone();
            write_status_file(&queued_status);
        }
        if guard.worker_running {
            false
        } else {
            guard.worker_running = true;
            true
        }
    };

    if should_spawn {
        let generation = GENERATION.load(Ordering::Acquire);
        thread::spawn(move || worker_loop(generation));
    }

    queued_status
}

fn pop_task(generation: u64) -> Option<ModuleRestartTask> {
    if generation != GENERATION.load(Ordering::Acquire) {
        return None;
    }
    let mut guard = queue().lock().ok()?;
    let key = guard.pending.keys().next().cloned()?;
    guard.pending.remove(&key)
}

fn mark_worker_stopped_if_empty(generation: u64) -> bool {
    let mut guard = match queue().lock() {
        Ok(guard) => guard,
        Err(_) => return true,
    };
    if generation != GENERATION.load(Ordering::Acquire) {
        guard.worker_running = false;
        guard.pending.clear();
        return true;
    }
    if guard.pending.is_empty() {
        guard.worker_running = false;
        true
    } else {
        false
    }
}

fn worker_loop(generation: u64) {
    loop {
        let Some(task) = pop_task(generation) else {
            if mark_worker_stopped_if_empty(generation) {
                return;
            }
            continue;
        };

        set_status(ModuleRestartStatus::for_task(
            &task.program,
            "running",
            format!("Перезапускаю модуль {}", task.program),
            None,
        ));

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            restart_program(generation, &task.program)
        }));

        if generation != GENERATION.load(Ordering::Acquire) {
            return;
        }

        match result {
            Ok(Ok(())) => {
                set_status(ModuleRestartStatus::for_task(
                    &task.program,
                    "success",
                    format!("Модуль {} перезапущен", task.program),
                    None,
                ));
            }
            Ok(Err(e)) => {
                let err = format!("{e:#}");
                log::warn!("module_restart: failed for {}: {err}", task.program);
                set_status(ModuleRestartStatus::for_task(
                    &task.program,
                    "failed",
                    "Настройка сохранена, но перезапустить модуль не удалось".to_string(),
                    Some(err),
                ));
            }
            Err(_) => {
                log::error!("module_restart: worker panicked for {}", task.program);
                set_status(ModuleRestartStatus::for_task(
                    &task.program,
                    "failed",
                    "Настройка сохранена, но перезапуск аварийно завершился".to_string(),
                    Some("module restart panic".to_string()),
                ));
            }
        }
    }
}

fn restart_program(generation: u64, program: &str) -> Result<()> {
    match program {
        "operaproxy" => {
            // Stop half: kill only this module's processes and remove only its
            // rules, leaving every other program untouched.
            operaproxy::stop_module()?;

            // A full start/stop may have begun while we were stopping. Bail out
            // before starting anything so we never resurrect a module the
            // global stop just killed.
            if generation != GENERATION.load(Ordering::Acquire) {
                log::info!("module_restart: cancelled before start phase (operaproxy)");
                return Ok(());
            }

            // Start half: re-reads every config file from disk.
            operaproxy::start_if_enabled()
        }
        other => anyhow::bail!("module_restart: unsupported program {other}"),
    }
}

// --- t2s coordination helpers ----------------------------------------------
//
// t2s is shared by many programs, so a module restart must never kill it by
// name. Each running t2s writes an instance metadata file under
// /data/adb/modules/ZDT-D/api/t2s/instances/ with its pid and program, which
// gives us an exact list of the instances belonging to one program.

/// PIDs of the t2s instances owned by `program`, read from the metadata each
/// instance writes at startup, with a /proc scan as fallback.
pub(crate) fn t2s_pids_for_program(program: &str) -> Vec<i32> {
    let mut pids = read_t2s_instance_pids(program);
    if pids.is_empty() {
        pids.extend(proc_scan_t2s_pids_for_program(program));
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

/// Fallback: scan /proc/*/cmdline for a t2s process carrying `--program <program>`.
/// The kernel null-separates argv, so we match tokens exactly: no shell regex,
/// no pgrep self-match, and the `.bin` binary suffix is handled naturally.
fn proc_scan_t2s_pids_for_program(program: &str) -> Vec<i32> {
    let mut pids = Vec::new();
    let entries = match fs::read_dir("/proc") {
        Ok(e) => e,
        Err(_) => return pids,
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<i32>().ok()) else { continue };
        if pid <= 1 || pid == std::process::id() as i32 {
            continue;
        }
        let Ok(cmd) = fs::read(entry.path().join("cmdline")) else { continue };
        let mut tokens = cmd.split(|b| *b == 0)
            .map(|tok| std::str::from_utf8(tok).unwrap_or(""))
            .filter(|tok| !tok.is_empty());
        // argv[0] is the binary path; accept both t2s and t2s.bin.
        let is_t2s = tokens
            .next()
            .map(|bin| {
                let base = bin.rsplit('/').next().unwrap_or(bin);
                base == "t2s" || base == "t2s.bin"
            })
            .unwrap_or(false);
        if !is_t2s {
            continue;
        }
        let mut belongs = false;
        while let Some(tok) = tokens.next() {
            if tok == "--program" && tokens.next() == Some(program) {
                belongs = true;
                break;
            }
        }
        if belongs && pid_alive(pid) {
            pids.push(pid);
        }
    }
    pids
}

fn read_t2s_instance_pids(program: &str) -> Vec<i32> {
    let mut pids = Vec::new();
    let entries = match fs::read_dir(T2S_INSTANCES_DIR) {
        Ok(e) => e,
        Err(_) => return pids,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let Ok(raw) = fs::read_to_string(&path) else { continue };
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { continue };
        if v.get("program").and_then(|x| x.as_str()) != Some(program) {
            continue;
        }
        if let Some(pid) = v.get("pid").and_then(|x| x.as_u64()) {
            let pid = pid as i32;
            if pid > 1 && pid_alive(pid) {
                pids.push(pid);
            }
        }
    }
    pids
}

fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

fn pid_alive(pid: i32) -> bool {
    Path::new("/proc").join(pid.to_string()).is_dir()
}

/// Kill the given PIDs with a SIGTERM -> SIGKILL escalation, waiting for each
/// to actually exit so the port is free before we rebind.
pub(crate) fn kill_pids(label: &str, pids: &[i32]) -> Result<()> {
    if pids.is_empty() {
        return Ok(());
    }
    for pid in pids {
        let _ = shell::ok_sh(&format!("kill -15 {pid}"));
    }
    for _ in 0..15 {
        if pids.iter().all(|p| !pid_alive(*p)) {
            return Ok(());
        }
        thread::sleep(std::time::Duration::from_millis(100));
    }
    for pid in pids {
        if pid_alive(*pid) {
            let _ = shell::ok_sh(&format!("kill -9 {pid}"));
        }
    }
    for _ in 0..10 {
        if pids.iter().all(|p| !pid_alive(*p)) {
            return Ok(());
        }
        thread::sleep(std::time::Duration::from_millis(100));
    }
    log::warn!("module_restart: failed to kill {label}: {pids:?}");
    Ok(())
}

/// Kill processes by name via `pidof` (only safe for binaries used by a single
/// program, e.g. opera-proxy).
pub(crate) fn kill_by_name(name: &str) -> Result<()> {
    let (rc, out) = shell::run("pidof", &[name], shell::Capture::Stdout).unwrap_or((1, String::new()));
    if rc != 0 {
        return Ok(());
    }
    let pids = parse_pid_lines(&out);
    kill_pids(&format!("pidof {name}"), &pids)
}

/// Remove the t2s instance metadata left behind by processes we just killed: a
/// killed t2s never runs its own cleanup, and stale files confuse the shared
/// peer-coordination layer and the stats UI.
pub(crate) fn cleanup_stale_t2s_instance_files(program: &str) {
    let entries = match fs::read_dir(T2S_INSTANCES_DIR) {
        Ok(e) => e,
        Err(_) => return,
    };

    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let Ok(raw) = fs::read_to_string(&path) else { continue };
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { continue };
        if v.get("program").and_then(|x| x.as_str()) != Some(program) {
            continue;
        }
        // Only remove files whose process is actually gone.
        let pid = v.get("pid").and_then(|x| x.as_u64()).unwrap_or(0) as i32;
        if pid > 1 && pid_alive(pid) {
            continue;
        }
        let _ = fs::remove_file(&path);
        // Drop the matching port index file too.
        if let Some(web_port) = v.get("web_port").and_then(|x| x.as_u64()) {
            let _ = fs::remove_file(Path::new(T2S_PORTS_DIR).join(format!("{web_port}.json")));
        }
    }
}

// --- routing cleanup -------------------------------------------------------

// The routing-cache cleanup (delete scoped iptables chains + drop cached
// snapshots for this module's uid files) lives in runtime_refresh and is called
// directly by operaproxy::stop_module().
