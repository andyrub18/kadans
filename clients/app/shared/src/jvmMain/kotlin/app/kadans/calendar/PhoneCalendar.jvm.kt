package app.kadans.calendar

// A computer has no one calendar every program writes to; there, the calendar feed puts the todos in the person's own.
actual fun platformPhoneCalendar(): PhoneCalendarSource = NoPhoneCalendar
