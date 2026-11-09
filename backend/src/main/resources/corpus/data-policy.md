# Document Lifecycle 文件生命週期

## Import 匯入
Markdown import accepts a unique document ID, a title and body text. A document can contain at most 200000 characters. Headings alone are rejected. 文件匯入需要識別碼、標題與 Markdown 內文。

## Chunk identity 段落識別
Chunks split at headings and blank lines with a maximum of 800 characters. Chunk IDs use the document ID, heading, body SHA-256 prefix and duplicate occurrence. Unchanged paragraph content under the same heading keeps its chunk ID across updates. 段落識別碼由內容雜湊產生。

## Update 更新
Document update requires expectedVersion. A stale version returns HTTP 409 VERSION_CONFLICT. A changed document increments its version and atomically replaces active chunks. Removed old text is no longer retrievable. 文件更新使用版本檢查，舊內容不再出現在檢索中。

## Delete 刪除
Document deletion removes all active chunks. Version counters survive deletion, so recreating an ID gets a higher version. Old citations return 404 after deletion or 409 after recreation. 文件刪除後不能再取得原引用。

## Persistence 儲存
The JSON store writes a temporary file, forces file contents and atomically replaces the active snapshot. A file lock allows one backend writer. Documents persist across restarts. There is no historical revision archive and no global database service. 文件以本機 JSON 儲存，重啟仍保留資料。
