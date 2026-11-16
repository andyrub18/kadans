package app.kadans.calendar

// The iPhone's calendar (EventKit) comes with the iPhone app.
actual fun platformPhoneCalendar(): PhoneCalendarSource = NoPhoneCalendar
