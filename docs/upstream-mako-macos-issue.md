# Draft upstream issue: Mako Server build fails on macOS with Apple clang 21

Repository: https://github.com/RealTimeLogic/BAS (not filed yet; post it from your own GitHub account if you want it fixed upstream)

**Title:** macOS build fails: static `getmode` in BAS.c conflicts with `<unistd.h>`

**Environment:** macOS (arm64), Apple clang 21.0.0 (Command Line Tools), `LinuxBuild.sh` from `main`.

**Error:**
```
src/BAS.c:15027:12: error: static declaration of 'getmode' follows non-static declaration
src/BAS.c:15042:22: error: incompatible pointer to integer conversion passing 'Table *' to parameter of type 'mode_t'
```
The macOS SDK declares `mode_t getmode(const void *, mode_t)` in `<unistd.h>`; BAS.c defines its own static
`getmode` with a different signature.

**Workaround:** rename the internal function, e.g. `perl -pi -e 's/\bgetmode\b/bas_getmode/g' src/BAS.c`, then
`make -f mako.mk`. The resulting server runs normally (tested with an HTTP edge application posting telemetry).

**Suggested fix:** give the internal Lua helper a non-clashing name in the amalgamation source.
