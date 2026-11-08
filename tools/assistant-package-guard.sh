#!/system/bin/sh
# Started explicitly via ADB; no network listener, arbitrary commands, or key filtering.
# Requests and acknowledgements require WRITE_SECURE_SETTINGS (not an ordinary app permission).
pid_file=/data/local/tmp/assistant-package-guard.pid
if [ -f "$pid_file" ]; then
    previous_pid=$(cat "$pid_file")
    case "$previous_pid" in
        *[!0-9]*|'') ;;
        *)
            # cat, not a redirection: a redirection error is printed before 2>/dev/null applies,
            # and a dead pid put that error into the guard's log.
            previous_command=$(cat "/proc/$previous_pid/cmdline" 2>/dev/null | tr '\000' ' ')
            case "$previous_command" in
                *"sh /data/local/tmp/assistant-package-guard.sh"*) exit 0 ;;
            esac
            ;;
    esac
fi
echo $$ > "$pid_file"
trap 'rm -f "$pid_file"; exit' HUP INT TERM
request_key=ru_pda_tvhome_assistant_request
ack_key=ru_pda_tvhome_assistant_ack
package=com.google.android.katniss
settings delete secure "$ack_key" >/dev/null
last_request=
while pm path ru.pda.tvhome >/dev/null 2>&1; do
    request=$(settings get secure "$request_key")
    if [ "$request" != "$last_request" ]; then
        mode=${request%%:*}
        case "$mode" in
            check) settings put secure "$ack_key" "$request" ;;
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
    sleep 1
done
