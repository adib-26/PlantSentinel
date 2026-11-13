<?lsp
local s = app.status or {}
local c = app.cfg or {}
local function esc(v) return (tostring(v or "–"):gsub("[<>&\"]", { ["<"]="&lt;", [">"]="&gt;", ["&"]="&amp;", ['"']="&quot;" })) end
?>
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>BAS edge node</title>
<meta http-equiv="refresh" content="10">
<style>body{font-family:sans-serif;margin:2rem}td{padding:.2rem 1rem .2rem 0}</style></head>
<body>
<h1>BAS edge node <?lsp=esc(c.gateway)?></h1>
<table>
<tr><td>Server</td><td><?lsp=esc(c.url)?></td></tr>
<tr><td>Machines</td><td><?lsp=esc(table.concat(c.machines or {}, ", "))?></td></tr>
<tr><td>Readings sent</td><td><?lsp=esc(s.sent)?></td></tr>
<tr><td>Queued (store-and-forward)</td><td><?lsp=esc(s.queued)?></td></tr>
<tr><td>Rejected by server</td><td><?lsp=esc(s.failed)?></td></tr>
<tr><td>Dropped (queue full)</td><td><?lsp=esc(s.dropped)?></td></tr>
<tr><td>Last HTTP status</td><td><?lsp=esc(s.lastStatus)?></td></tr>
<tr><td>Last successful send</td><td><?lsp=esc(s.lastSentAt)?></td></tr>
<tr><td>Last error</td><td><?lsp=esc(s.lastError)?></td></tr>
</table>
</body>
</html>
