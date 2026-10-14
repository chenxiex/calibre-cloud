--[[--
Calibre Cloud integration (spec R37): tells the Calibre Cloud app on the same Android device to mark the
book being read as read or unread in its Calibre library.

The book is named by its file name, which Calibre Cloud chose when it handed the book over
("Title-ID.ext"); the app only accepts books it opened itself, in its current library. Requests are
URIs `<scheme>://<operation>?token=…&file=…`, sent as an explicit broadcast (stay in KOReader) or an
explicit VIEW intent (open Calibre Cloud). Registration sends a token this plugin generated; the app asks
the user and remembers only its hash. Nothing here reads the reply: the app reports rejections itself.

Optionally (Q93), once "mark as read" was sent, KOReader's own copy of the book in its import folder is deleted
like KOReader deletes a file, with or without its sidecar (.sdr); a book that is open is deleted after it closes.

@module koplugin.calibrecloud
--]]

local BookList = require("ui/widget/booklist")
local BookStatusWidget = require("ui/widget/bookstatuswidget")
local ConfirmBox = require("ui/widget/confirmbox")
local Device = require("device")
local DocSettings = require("docsettings")
local InfoMessage = require("ui/widget/infomessage")
local ReadCollection = require("readcollection")
local ReadHistory = require("readhistory")
local ReaderStatus = require("apps/reader/modules/readerstatus")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local ffiutil = require("ffi/util")
local filemanagerutil = require("apps/filemanager/filemanagerutil")
local logger = require("logger")
local util = require("util")
local GetText = require("gettext")

local RELEASE_PACKAGE = "io.github.chenxiex.calibrecloud"
local DEBUG_PACKAGE = "io.github.chenxiex.calibrecloud.debug"
local RECEIVER = "io.github.chenxiex.calibrecloud.control.ExternalControlReceiver"
local ACTION = "io.github.chenxiex.calibrecloud.action.EXTERNAL_REQUEST"
local SETTINGS = "calibrecloud"

-- KOReader's catalogue does not know these strings, so the plugin carries its own Chinese text.
local zh = {
    ["Calibre Cloud"] = "Calibre Cloud",
    ["Mark as read in Calibre Cloud"] = "在 Calibre Cloud 中标为已读",
    ["Mark as unread in Calibre Cloud"] = "在 Calibre Cloud 中标为未读",
    ["Follow book status automatically"] = "自动跟随书籍状态",
    ["After marking as read"] = "标为已读后",
    ["After marking as unread"] = "标为未读后",
    ["Open Calibre Cloud"] = "打开 Calibre Cloud",
    ["Stay in KOReader"] = "停留在 KOReader",
    ["Register with Calibre Cloud"] = "注册到 Calibre Cloud",
    ["Register again if Calibre Cloud was reinstalled or the registration was removed."] = "如果重装了 Calibre Cloud 或移除了登记，请再次注册。",
    ["Calibre Cloud is not installed."] = "未安装 Calibre Cloud。",
    ["Target app"] = "目标应用",
    ["Calibre Cloud Debug"] = "Calibre Cloud Debug",
    ["This plugin only works on Android."] = "此插件只能在 Android 上使用。",
    ["Register with Calibre Cloud first."] = "请先注册到 Calibre Cloud。",
    ["Only books opened from Calibre Cloud can be marked."] = "只能标记由 Calibre Cloud 打开的书。",
    ["Sent to Calibre Cloud."] = "已发送到 Calibre Cloud。",
    ["Could not reach Calibre Cloud."] = "无法联系 Calibre Cloud。",
    ["Delete KOReader's copy after marking as read"] = "标为已读后删除 KOReader 中的副本",
    ["Also delete reading data (.sdr)"] = "同时删除阅读数据（.sdr）",
    ["Enable"] = "开启",
    ["After a book is sent to Calibre Cloud as read, KOReader's copy of it will be deleted:\n%1/Title-ID.ext\n\nOnly books KOReader imported from Calibre Cloud into this folder are deleted. A book that is open is deleted when you close it, unless you mark it as unread first. As when deleting a file in KOReader, the book is also removed from collections and dimmed or removed in History.\n\nIts reading data (.sdr) is kept unless you also turn on deleting it, so progress and highlights come back when you open the book from Calibre Cloud again. The book stays in Calibre Cloud and in your Calibre library."]
        = "在 KOReader 中把书标为已读并发送到 Calibre Cloud 后，将删除 KOReader 中的副本：\n%1/书名-ID.后缀\n\n只删除 KOReader 从 Calibre Cloud 导入到此文件夹的书。正在阅读的书在关闭后删除，关闭前标为未读则不删除。与在 KOReader 中删除文件一样，该书也会移出收藏，并在历史记录中变灰或移除。\n\n除非同时开启删除阅读数据，否则保留该书的阅读数据（.sdr），以后从 Calibre Cloud 再次打开时恢复进度和标注。该书仍保留在 Calibre Cloud 和 Calibre 书库中。",
    ["When KOReader's copy is deleted, its reading data (.sdr) will be deleted too: reading progress, highlights, notes, bookmarks, book status, per-book settings, and any custom cover or metadata.\n\nOpening the book from Calibre Cloud again starts from scratch. This cannot be undone."]
        = "删除 KOReader 中的副本时，将同时删除其阅读数据（.sdr）：阅读进度、高亮、笔记、书签、书籍状态、单书设置，以及自定义封面或元数据。\n\n以后从 Calibre Cloud 再次打开该书时从头开始。此操作无法撤销。",
}

local function _(text)
    if (GetText.current_lang or ""):match("^zh") and zh[text] then
        return zh[text]
    end
    return GetText(text)
end

local function settings()
    return G_reader_settings:readSetting(SETTINGS) or {}
end

local function saveSetting(key, value)
    local values = settings()
    values[key] = value
    G_reader_settings:saveSetting(SETTINGS, values)
end

local function setting(key, default)
    local value = settings()[key]
    if value == nil then
        return default
    end
    return value
end

--- The installed Calibre Cloud: the chosen build (release by default), else whichever one is installed.
local function targetPackage()
    local android = require("android")
    local chosen = setting("package", RELEASE_PACKAGE)
    for _i, package in ipairs({ chosen, chosen == DEBUG_PACKAGE and RELEASE_PACKAGE or DEBUG_PACKAGE }) do
        if android.isPackageEnabled(package) then
            return package
        end
    end
end

--- Whether both builds are installed, so that a developer can choose which one receives requests.
local function bothInstalled()
    if not Device:isAndroid() then
        return false
    end
    local android = require("android")
    return android.isPackageEnabled(RELEASE_PACKAGE) and android.isPackageEnabled(DEBUG_PACKAGE)
end

--- Sends the request URI to [package]; [open] starts the app's Activity, otherwise a broadcast keeps KOReader in front.
local function deliver(package, uri, open)
    local android = require("android")
    return android.jni:context(android.app.activity.vm, function(jni)
        local env = jni.env
        local refs = {}
        local function ref(object)
            table.insert(refs, object)
            return object
        end
        local function jstring(value)
            return ref(env[0].NewStringUTF(env, value))
        end
        local parsed = ref(jni:callStaticObjectMethod("android/net/Uri", "parse",
            "(Ljava/lang/String;)Landroid/net/Uri;", jstring(uri)))
        local intent_class = ref(env[0].FindClass(env, "android/content/Intent"))
        local constructor = env[0].GetMethodID(env, intent_class, "<init>", "(Ljava/lang/String;Landroid/net/Uri;)V")
        local intent = ref(env[0].NewObject(env, intent_class, constructor,
            jstring(open and "android.intent.action.VIEW" or ACTION), parsed))
        if open then
            ref(jni:callObjectMethod(intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;", jstring(package)))
            jni:callVoidMethod(android.app.activity.clazz, "startActivity", "(Landroid/content/Intent;)V", intent)
        else
            ref(jni:callObjectMethod(intent, "setClassName",
                "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;", jstring(package), jstring(RECEIVER)))
            jni:callVoidMethod(android.app.activity.clazz, "sendBroadcast", "(Landroid/content/Intent;)V", intent)
        end
        local failed = env[0].ExceptionCheck(env) ~= 0
        if failed then
            env[0].ExceptionClear(env)
        end
        for _i, object in ipairs(refs) do
            if object ~= nil then
                env[0].DeleteLocalRef(env, object)
            end
        end
        return not failed
    end)
end

--- Builds and sends one request; returns false with a message when it could not be sent.
local function send(operation, parameters, open)
    if not Device:isAndroid() then
        return false, _("This plugin only works on Android.")
    end
    local package = targetPackage()
    if not package then
        return false, _("Calibre Cloud is not installed.")
    end
    local query = {}
    for _i, key in ipairs({ "token", "file", "name" }) do
        if parameters[key] then
            table.insert(query, key .. "=" .. util.urlEncode(parameters[key]))
        end
    end
    local scheme = package == DEBUG_PACKAGE and "calibrecloud-debug" or "calibrecloud"
    local ok, delivered = pcall(deliver, package, scheme .. "://" .. operation .. "?" .. table.concat(query, "&"), open)
    if not ok or not delivered then
        -- The URI holds the token, so only the operation is logged.
        logger.warn("CalibreCloud: could not send", operation, ok and "" or delivered)
        return false, _("Could not reach Calibre Cloud.")
    end
    return true
end

--- Only names Calibre Cloud gives ("Title-ID.ext") can match; other books are left alone.
local function bookName(file)
    local _dir, name = util.splitFilePathName(file or "")
    if name and name:match("%-%d+%.[%w]+$") then
        return name
    end
end

--- The folder KOReader copies `content://` books into (`getExternalFilesDir(null)`), resolved.
local function importFolder()
    if not Device:isAndroid() then
        return nil
    end
    local ffi = require("ffi")
    local path = require("android").app.activity.externalDataPath
    return path ~= nil and ffiutil.realpath(ffi.string(path)) or nil
end

--- The resolved path of [file] when it is a copy KOReader imported from Calibre Cloud, else nil.
local function importedCopy(file)
    local path = file and bookName(file) and ffiutil.realpath(file)
    local folder = importFolder()
    if path and folder and util.splitFilePathName(path) == folder .. "/" then
        return path
    end
end

--- Deletes an imported copy as KOReader's file manager does; its sidecar only if the user chose so.
local function deleteCopy(file)
    if not os.remove(file) then
        logger.warn("CalibreCloud: could not delete the imported copy", file)
        return
    end
    BookList.resetBookInfoCache(file)
    if setting("remove_sdr", false) then
        DocSettings.updateLocation(file) -- deletes the sidecar
    end
    ReadHistory:fileDeleted(file)
    ReadCollection:removeItem(file)
    logger.info("CalibreCloud: deleted the imported copy", file)
    local FileManager = require("apps/filemanager/filemanager")
    if FileManager.instance then
        FileManager.instance:onRefresh()
    end
end

-- Open books whose copy is deleted once the reader closes them: deleting earlier would pull the book from
-- under the reader, and closing it writes the sidecar again.
local closing = {}

--- Follows a request that was sent for [file]: deletes its imported copy after "read" when the user chose so.
local function sent(file, read)
    local path = importedCopy(file)
    if not path then
        return
    end
    if not read then
        closing[path] = nil
        return
    end
    if not setting("remove_copy", false) then
        return
    end
    local ReaderUI = require("apps/reader/readerui")
    local reader = ReaderUI.instance
    local open = reader and reader.document and ffiutil.realpath(reader.document.file)
    if open == path then
        closing[path] = true
    else
        deleteCopy(path)
    end
end

-- Status changes waiting for the next tick, so a multi-book change opens Calibre Cloud at most once.
local queued = {}
local scheduled = false

local function flush()
    scheduled = false
    local token = setting("token")
    local requests = queued
    queued = {}
    if not token then
        return
    end
    local function request(file, open)
        local read = requests[file]
        if send(read and "mark_read" or "mark_unread", { token = token, file = bookName(file) }, open) then
            sent(file, read)
        end
    end
    local opener
    for file, read in pairs(requests) do
        if setting(read and "open_on_read" or "open_on_unread", read) and not opener then
            opener = file
        else
            request(file, false)
        end
    end
    -- The request that opens the app goes last, after the broadcasts were handed over.
    if opener then
        request(opener, true)
    end
end

--- Follows a status change from [before] to [after] when it enters or leaves "complete".
local function statusChanged(file, before, after)
    if not setting("auto", true) or before == after then
        return
    end
    local read
    if after == "complete" then
        read = true
    elseif before == "complete" then
        read = false
    else
        return
    end
    if not bookName(file) then
        return
    end
    queued[file] = read
    if not scheduled then
        scheduled = true
        UIManager:nextTick(flush)
    end
end

local function summaryStatus(doc_settings)
    local summary = doc_settings and doc_settings:readSetting("summary")
    return summary and summary.status
end

-- KOReader has no event for status changes, so the three places that set a status are wrapped once.
if not ReaderStatus._calibrecloud_wrapped then
    ReaderStatus._calibrecloud_wrapped = true

    local markBook = ReaderStatus.markBook
    ReaderStatus.markBook = function(self, mark_read)
        local before = summaryStatus(self.ui.doc_settings)
        local result = markBook(self, mark_read)
        statusChanged(self.document.file, before, summaryStatus(self.ui.doc_settings))
        return result
    end

    local onChangeBookStatus = BookStatusWidget.onChangeBookStatus
    BookStatusWidget.onChangeBookStatus = function(self, option_name, option_value)
        local before = self.summary.status
        local result = onChangeBookStatus(self, option_name, option_value)
        statusChanged(self.ui.document.file, before, self.summary.status)
        return result
    end

    -- The file manager has set the new status on the summary already; its list cache still has the old one.
    local saveSummary = filemanagerutil.saveSummary
    filemanagerutil.saveSummary = function(doc_settings_or_file, summary)
        local file = type(doc_settings_or_file) == "table" and doc_settings_or_file:readSetting("doc_path") or doc_settings_or_file
        local before = file and BookList.getBookStatus(file)
        local result = saveSummary(doc_settings_or_file, summary)
        statusChanged(file, before, summary.status)
        return result
    end
end

local CalibreCloud = WidgetContainer:extend{
    name = "calibrecloud",
    is_doc_only = false,
}

function CalibreCloud:init()
    self.ui.menu:registerToMainMenu(self)
end

local function show(text)
    UIManager:show(InfoMessage:new{ text = text, timeout = 3 })
end

function CalibreCloud:markCurrent(read)
    local token = setting("token")
    if not token then
        return show(_("Register with Calibre Cloud first."))
    end
    local file = self.ui.document and self.ui.document.file
    local name = bookName(file)
    if not name then
        return show(_("Only books opened from Calibre Cloud can be marked."))
    end
    local open = setting(read and "open_on_read" or "open_on_unread", read)
    local ok, problem = send(read and "mark_read" or "mark_unread", { token = token, file = name }, open)
    if not ok then
        return show(problem)
    end
    sent(file, read)
    if not open then
        show(_("Sent to Calibre Cloud."))
    end
end

function CalibreCloud:onCloseDocument()
    local path = importedCopy(self.ui.document and self.ui.document.file)
    if path and closing[path] then
        closing[path] = nil
        -- ReaderUI saves the sidecar after this event; delete once it is done.
        UIManager:nextTick(function() deleteCopy(path) end)
    end
end

--- The plugin's token, generated once. Registering again resends it, so cancelling a later prompt (or
--- registering with a reinstalled app) never invalidates a registration that already works.
local function token()
    local value = setting("token")
    if value then
        return value
    end
    local random = io.open("/dev/urandom", "rb")
    if not random then
        return nil
    end
    local bytes = random:read(24)
    random:close()
    value = bytes:gsub(".", function(c) return string.format("%02x", c:byte()) end)
    saveSetting("token", value)
    return value
end

function CalibreCloud:register()
    local value = token()
    if not value then
        return show(_("Could not reach Calibre Cloud."))
    end
    local ok, problem = send("register", { token = value, name = "KOReader" }, true)
    if not ok then
        show(problem)
    end
end

local function afterChoice(key, default)
    return {
        text_func = function()
            local open = setting(key, default)
            return (key == "open_on_read" and _("After marking as read") or _("After marking as unread")) .. ": "
                .. (open and _("Open Calibre Cloud") or _("Stay in KOReader"))
        end,
        sub_item_table = {
            {
                text = _("Open Calibre Cloud"),
                radio = true,
                checked_func = function() return setting(key, default) end,
                callback = function() saveSetting(key, true) end,
            },
            {
                text = _("Stay in KOReader"),
                radio = true,
                checked_func = function() return not setting(key, default) end,
                callback = function() saveSetting(key, false) end,
            },
        },
    }
end

--- A deletion option that asks before it is turned on; turning it off needs no confirmation.
local function deletionChoice(key, text, explanation, enabled_func)
    return {
        text = text,
        enabled_func = enabled_func,
        keep_menu_open = true,
        checked_func = function() return setting(key, false) end,
        callback = function(touchmenu_instance)
            if setting(key, false) then
                return saveSetting(key, false)
            end
            UIManager:show(ConfirmBox:new{
                text = explanation(),
                ok_text = _("Enable"),
                ok_callback = function()
                    saveSetting(key, true)
                    touchmenu_instance:updateItems()
                end,
            })
        end,
    }
end

function CalibreCloud:addToMainMenu(menu_items)
    local items = {}
    if self.ui.document then
        table.insert(items, {
            text = _("Mark as read in Calibre Cloud"),
            callback = function() self:markCurrent(true) end,
        })
        table.insert(items, {
            text = _("Mark as unread in Calibre Cloud"),
            callback = function() self:markCurrent(false) end,
            separator = true,
        })
    end
    table.insert(items, {
        text = _("Follow book status automatically"),
        checked_func = function() return setting("auto", true) end,
        callback = function() saveSetting("auto", not setting("auto", true)) end,
    })
    table.insert(items, afterChoice("open_on_read", true))
    table.insert(items, afterChoice("open_on_unread", false))
    table.insert(items, deletionChoice("remove_copy", _("Delete KOReader's copy after marking as read"), function()
        return ffiutil.template(_("After a book is sent to Calibre Cloud as read, KOReader's copy of it will be deleted:\n%1/Title-ID.ext\n\nOnly books KOReader imported from Calibre Cloud into this folder are deleted. A book that is open is deleted when you close it, unless you mark it as unread first. As when deleting a file in KOReader, the book is also removed from collections and dimmed or removed in History.\n\nIts reading data (.sdr) is kept unless you also turn on deleting it, so progress and highlights come back when you open the book from Calibre Cloud again. The book stays in Calibre Cloud and in your Calibre library."),
            importFolder() or "Android/data/…/files")
    end))
    table.insert(items, deletionChoice("remove_sdr", _("Also delete reading data (.sdr)"), function()
        return _("When KOReader's copy is deleted, its reading data (.sdr) will be deleted too: reading progress, highlights, notes, bookmarks, book status, per-book settings, and any custom cover or metadata.\n\nOpening the book from Calibre Cloud again starts from scratch. This cannot be undone.")
    end, function() return setting("remove_copy", false) end))
    table.insert(items, {
        text = _("Register with Calibre Cloud"),
        help_text = _("Register again if Calibre Cloud was reinstalled or the registration was removed."),
        keep_menu_open = true,
        callback = function() self:register() end,
    })
    -- Only developers have both builds; tokens are registered per build, so register again after switching.
    if bothInstalled() then
        local function choice(text, package)
            return {
                text = text,
                radio = true,
                checked_func = function() return targetPackage() == package end,
                callback = function() saveSetting("package", package) end,
            }
        end
        table.insert(items, {
            text = _("Target app"),
            sub_item_table = { choice(_("Calibre Cloud"), RELEASE_PACKAGE), choice(_("Calibre Cloud Debug"), DEBUG_PACKAGE) },
        })
    end
    menu_items.calibrecloud = {
        text = _("Calibre Cloud"),
        sorting_hint = "tools",
        sub_item_table = items,
    }
end

return CalibreCloud
