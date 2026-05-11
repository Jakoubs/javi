$env:JAVA_TOOL_OPTIONS='--add-opens=java.base/java.lang=ALL-UNNAMED'
Set-Location 'D:\SoftwareArchitektur\javi'
sbt --no-server "rest/runMain chess.rest.LichessDbOpeningImportMain 2026-04 20 400000"
