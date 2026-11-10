#!/system/bin/sh
# Started explicitly via ADB; no network listener, arbitrary commands, or key filtering.
# Requests and acknowledgements require WRITE_SECURE_SETTINGS (not an ordinary app permission).
# Modes: check, launcher, system, grant, freeze:<pkg>:<uuid>, unfreeze:<pkg>:<uuid>.
# A package outside this protected mirror of the launcher's own safe list is never touched,
# and a refused request answers nothing, so the launcher settings row reports no answer.
tmp_dir=${TVHOME_TMP:-/data/local/tmp}
pid_file=$tmp_dir/assistant-package-guard.pid
# No single-instance exit on the recorded pid: the file survives a reboot, its pid gets reused by
# another process, and on 2026-11-10 every boot instance exited reading its own stale record.
# The ensure script is the single-instance point, and a duplicated instance answers the same way,
# so the guard only records its pid here and keeps running.
# No exit trap on signals: the boot instance died from a signal right after the helper session
# closed on 2026-11-10, so the rows went silent until the next ensure call. The signals are ignored
# instead; the ensure script starts the guard from settings, so surviving them is what matters.
echo $$ > "$pid_file"
trap : HUP INT TERM
trace_file=$tmp_dir/assistant-package-guard.trace
echo "pid $$ started" >> "$trace_file"
dex_file=$tmp_dir/tvhome-remote-2.dex
bridge_log=$tmp_dir/tvhome-remote-2.log
bridge_name=pqtv-bridge
# How many passes to skip before looking for the remote again; see the bridge block below.
search_pause=0
[ -f "$dex_file" ] || echo "no dex" >> "$trace_file"
request_key=ru_pda_tvhome_assistant_request
ack_key=ru_pda_tvhome_assistant_ack
package=com.google.android.katniss
protected=" ru.pda.tvhome android com.android.tv.settings com.mediatek.tvinput com.google.android.tv.inputplayer com.mediatek.tv.inputplayeroverlay com.mediatek.tv.service com.mediatek.android.leanbacklauncher.partnercustomizer com.mediatek.androidbox com.mediatek.factorykey com.mediatek.wwtv.webview com.google.android.webview com.google.android.inputmethod.latin com.google.android.gms com.google.android.gsf com.google.android.play com.android.vending com.android.systemui com.android.providers.settings com.android.providers.tv com.android.providers.downloads com.google.android.tv.assistant com.google.android.tv.remote.service com.google.android.apps.mediashell com.google.android.apps.tv.dreamx com.google.android.tts com.dolby.android.audio.service com.mediatek.speakerservice com.homwee.dynamicbacklight com.homwee.lightsensorservice com.homwee.synckey com.homwee.upgrade com.homwee.dmt.upgrade com.homwee.mmp.screenshare com.homwee.multiscreenshare com.mediatek.autopair com.android.statementservice com.android.rkpdapp com.android.devicelockcontroller com.android.managedprovisioning com.android.packageinstaller com.google.android.packageinstaller com.android.keychain com.android.backupconfirm com.android.certinstaller com.android.tv.frameworkpackagestubs com.android.vpndialogs com.android.se com.android.bluetooth com.android.wifi.resources com.android.networkstack com.android.inputdevices "
settings delete secure "$ack_key" >/dev/null
# Remote gear: the stock shell reacts on its own and shows the old turnkey menu next to the launcher
# settings. The physical gear also goes to the factory receiver, which opens system settings next to
# the picture settings window; the owner decided on 2026-11-10 that the receiver package is frozen
# instead of protected. Both entries are frozen at every start; already off changes nothing.
# At boot pm answers nothing until the user is unlocked, so the guard never exits on a failed pm
# pass: it waits, freezes on the first working pass, and answers requests as soon as settings work.
# On 2026-11-10 a threshold exit killed the boot instance before unlock and the settings rows went
# silent on the freshly started TV. The trace file records where a boot instance stops, so the death
# can be read instead of guessed: start, waiting for pm, freeze done.
boot_freeze=0
pm_wait_logged=0
last_request=
while :; do
    if pm path ru.pda.tvhome >/dev/null 2>&1; then
        if [ "$boot_freeze" = 0 ]; then
            pm disable-user --user 0 com.mediatek.wwtv.tvcenter >/dev/null 2>&1
            pm disable-user --user 0 com.homwee.factory >/dev/null 2>&1
            pm disable-user --user 0 com.google.android.apps.tv.launcherx >/dev/null 2>&1
            # Home resolution: while the recovery stub is enabled the system resolves HOME to its
            # RecoveryActivity, which relaunches itself in a burst and shows nothing, so the Home key never
            # reaches TVHome from any app, the settings menu included - seen on the living-room TV on
            # 2026-11-11. It is frozen only when the initial setup is finished, so the setup wizard can still
            # run; the installer applies the same rule, and the trace records which way this pass went.
            if [ "$(settings get global device_provisioned)" = 1 ]; then
                pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1
                echo "home stub frozen" >> "$trace_file"
            else
                echo "home stub left on: initial setup not finished" >> "$trace_file"
            fi
            boot_freeze=1
            echo "freeze done" >> "$trace_file"
        fi
    elif [ "$pm_wait_logged" = 0 ]; then
        pm_wait_logged=1
        echo "waiting: pm not answering" >> "$trace_file"
    fi
    request=$(settings get secure "$request_key")
    if [ "$request" != "$last_request" ]; then
        mode=${request%%:*}
        arg=${request#"$mode":}
        case "$mode" in
            check) settings put secure "$ack_key" "$request" ;;
            grant)
                # The permissions the launcher declared but an app cannot grant to itself; the
                # settings menu has no other way to get them after a reinstall resets the state.
                if pm grant ru.pda.tvhome android.permission.RECORD_AUDIO >/dev/null 2>&1 &&
                        appops set ru.pda.tvhome RECORD_AUDIO allow >/dev/null 2>&1 &&
                        appops set ru.pda.tvhome SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 &&
                        appops set ru.pda.tvhome GET_USAGE_STATS allow >/dev/null 2>&1; then
                    settings put secure "$ack_key" "$request"
                fi
                ;;
            freeze)
                pkg=${arg%%:*}
                case "$protected" in *" $pkg "*) ;;
                    *)
                        # A package already off answers nothing from disable-user, so already-off is
                        # counted as the same state through the disabled list.
                        if pm disable-user --user 0 "$pkg" >/dev/null 2>&1 ||
                                pm list packages -d | grep -qx "$pkg"; then
                            settings put secure "$ack_key" "$request"
                        fi
                    ;;
                esac
                ;;
            unfreeze)
                pkg=${arg%%:*}
                case "$protected" in *" $pkg "*) ;;
                    *)
                        if pm enable --user 0 "$pkg" >/dev/null 2>&1; then
                            settings put secure "$ack_key" "$request"
                        fi
                    ;;
                esac
                ;;
            launcher)
                selected=$(settings get secure voice_interaction_service)
                if [ "$selected" = "ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService" ]; then
                    # A package already frozen answers nothing, so the launcher would wait five seconds
                    # and give up: already off counts as the same state.
                    if pm disable-user --user 0 "$package" >/dev/null 2>&1 ||
                            pm list packages -d | grep -qx "$package"; then
                        # Package removal can clear the selected assistant even when another one was
                        # selected first. Reassert our entry after that package operation completes.
                        settings put secure assistant ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService
                        settings put secure voice_interaction_service ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService
                        settings put secure "$ack_key" "$request"
                    fi
                fi
                ;;
            system)
                if pm enable --user 0 "$package" >/dev/null 2>&1; then
                    settings put secure "$ack_key" "$request"
                fi
                ;;
        esac
        last_request=$request
    fi
    # The gear bridge can only be started by the shell domain, so this loop is what keeps it running. A bridge
    # started straight from the helper's adbd session dies with that session, while the guard survives it, so the
    # bridge is the guard's own child here and is started again whenever it is gone. The remote is enumerated
    # late, so a missing one is searched again instead of given up on; the search only runs while the bridge is
    # not running, and is then throttled so a quiet guard stays quiet.
    if [ -f "$dex_file" ] && ! pidof "$bridge_name" >/dev/null 2>&1; then
        if [ "$search_pause" -le 0 ]; then
            device=$(getevent -lp 2>/dev/null | awk '
              /^add device/ { path = $NF }
              /name:.*TVC0GWBT Consumer Control/ { found = path }
              END { print found }')
            search_pause=15
            if [ -n "$device" ]; then
                size=16
                case "$(getprop ro.product.cpu.abi)" in
                    *64*) size=24 ;;
                esac
                CLASSPATH="$dex_file" setsid app_process /system/bin --nice-name="$bridge_name" PqRemoteProof "$device" "$size" 0 >>"$bridge_log" 2>&1 </dev/null &
                echo "bridge started on $device size $size" >> "$trace_file"
            else
                echo "no remote" >> "$trace_file"
            fi
        else
            search_pause=$((search_pause - 1))
        fi
    fi
    sleep 1
done
