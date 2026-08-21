on run argv
    if (count of argv) is not 1 then error "Expected one FIFO path"
    set fifoFile to POSIX file (item 1 of argv)
    set recipientAddress to read fifoFile until linefeed as «class utf8»
    set messageText to read fifoFile as «class utf8»
    tell application "Messages"
        set targetService to first service whose service type is iMessage
        set targetBuddy to buddy recipientAddress of targetService
        send messageText to targetBuddy
    end tell
end run
