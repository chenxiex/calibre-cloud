local _ = require("gettext")
return {
    name = "calibrecloud",
    fullname = _("Calibre Cloud"),
    description = _([[Marks books opened from Calibre Cloud as read or unread in their Calibre library. Android only.]]),
    -- Same as the app's versionName; the release workflow checks it.
    version = "1.2.0",
}
