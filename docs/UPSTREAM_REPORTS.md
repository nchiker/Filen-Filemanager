# Reports to Filen (the owner approves each one first)

Sent 2026-11-27 on the owner's go-ahead: #1 → https://github.com/FilenCloudDienste/filen-rs/issues/23,
#2 → https://github.com/FilenCloudDienste/filen-rs/issues/24.

SDK checked: filen-rs c93cedd (0.5.35) and origin/main (9 newer commits; only 617bc84 touches the SDK, a connect-timeout fix).

## 1. Missing: look up a received (shared-with-me) item, or its parent, by uuid
- API: `get_dir` / `get_dir_optional` / `get_file` / `get_file_optional` (own drive). There is no equivalent for an item
  that was shared with the signed-in user; `list_in_shared_root` and `list_shared_dir` are the only ways to reach one.
- Expected: a call that takes the uuid of a received file or folder and returns the item with its sharing role and its
  parent uuid (or "not shared with you any more").
- Actual: none exists in c93cedd or origin/main. A client that holds only a uuid (for example an Android document id kept
  after the app process was killed) must list the share root and every folder on the way down until the item turns up,
  and cannot tell "no longer shared" from "not found yet" without finishing that walk.
- Repro: share a folder with a nested folder and a file in it with a second account; on the second account keep the
  file's uuid, start a new client, and try to get the file, or its parent, by that uuid.
- Why it matters: apps that expose shared files to other apps through stable ids need this for cheap, reliable lookups
  and for telling "gone" from "offline".

## 2. Missing in the uniffi/JS client: recursive listing of a received folder
- API: `Client::list_shared_dir_recursive` exists in `filen-sdk-rs/src/connect/mod.rs` but has no wrapper in
  `connect/js_impls.rs`, so the generated Kotlin bindings have no `listSharedDirRecursive`
  (`list_dir_recursive` for the own drive does exist).
- Expected: the same recursive listing for received folders as for the own drive.
- Actual: clients list received trees one folder per call.
- Repro: generate the Kotlin bindings and look for `listSharedDirRecursive`.
- Why it matters: walking a shared tree is one request per folder; a recursive call would cut start-up and change-check time.
