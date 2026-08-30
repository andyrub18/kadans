using Ardalis.SmartEnum;
using Microsoft.AspNetCore.Http;

namespace Kadans.SharedKernel.Errors;

public sealed class ErrorTypes(string code, string title, int httpStatusCode, string rfcType)
    : SmartEnum<ErrorTypes, string>(title, code)
{
    public static readonly ErrorTypes Unauthorized = new(
        "10001",
        "Unauthorized",
        StatusCodes.Status401Unauthorized,
        ""
    );
    public static readonly ErrorTypes Forbidden = new(
        "10002",
        "Forbidden",
        StatusCodes.Status403Forbidden,
        ""
    );
    public static readonly ErrorTypes InvalidMonth = new(
        "10003",
        "InvalidMonth",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes PossibleInvalidSetPos = new(
        "10004",
        "PossibleInvalidSetPos",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes TaskAlreadyCompleted = new(
        "10005",
        "TaskAlreadyCompleted",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidMinute = new(
        "10007",
        "Invalid minute",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidHour = new(
        "10008",
        "Invalid hour",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidDayOfMonth = new(
        "10009",
        "Invalid day of month",
        StatusCodes.Status400BadRequest,
        "https://datatracker.ietf.org/doc/html/rfc2616#section-10.4.2"
    );
    public static readonly ErrorTypes InvalidInterval = new(
        "10010",
        "Invalid interval",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidFrequency = new(
        "10011",
        "Invalid frequency",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidStartDate = new(
        "10012",
        "Invalid start date",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidRecurrenceRule = new(
        "10013",
        "Invalid recurrence rule",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes NoNextOccurrenceFound = new(
        "10014",
        "No next occurrence found",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes ValidationError = new(
        "10015",
        "Validation error",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes DatabaseError = new(
        "10016",
        "Database error",
        StatusCodes.Status500InternalServerError,
        ""
    );
    public static readonly ErrorTypes TitleRequired = new(
        "10017",
        "Title is required",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes InvalidDueDate = new(
        "10018",
        "Invalid due date",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes TodoNotFound = new(
        "10019",
        "Todo not found",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes TaskAlreadyCancelled = new(
        "10020",
        "Task already cancelled",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes TodoOccurrenceNotFound = new(
        "10021",
        "Todo occurrence not found",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes UserNotFound = new(
        "10022",
        "User not found",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes NoFutureOccurrences = new(
        "10023",
        "No future occurrences",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes InvalidCredentials = new(
        "10024",
        "Invalid credentials",
        StatusCodes.Status401Unauthorized,
        ""
    );
    public static readonly ErrorTypes UserInactive = new(
        "10025",
        "User inactive",
        StatusCodes.Status403Forbidden,
        ""
    );
    public static readonly ErrorTypes PomodoroTemplateNotFound = new(
        "10026",
        "Pomodoro template not found",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes PomodoroTemplateInvalid = new(
        "10027",
        "Pomodoro template invalid",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes PomodoroRunNotFound = new(
        "10028",
        "Pomodoro run not found",
        StatusCodes.Status404NotFound,
        ""
    );
    public static readonly ErrorTypes PomodoroRunInvalidState = new(
        "10029",
        "Pomodoro run invalid state",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes PomodoroAlreadyActiveForTodo = new(
        "10030",
        "Pomodoro run already active for todo",
        StatusCodes.Status400BadRequest,
        ""
    );
    public static readonly ErrorTypes PomodoroTemplateRequired = new(
        "10031",
        "Pomodoro template required",
        StatusCodes.Status400BadRequest,
        ""
    );

    public static readonly ErrorTypes InvalidTimeZone = new(
        "10032",
        "Invalid time zone",
        StatusCodes.Status400BadRequest,
        ""
    );

    public int HttpStatusCode { get; } = httpStatusCode;
    public string RfcType { get; } = rfcType;
}
