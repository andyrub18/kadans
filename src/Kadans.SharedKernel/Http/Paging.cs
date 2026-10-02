using Kadans.SharedKernel.Errors;

namespace Kadans.SharedKernel.Http;

/// <summary>
/// The bounds every paged list shares: pages count from 1, and a page holds at most <see cref="MaxPageSize"/>
/// items. Page 0 used to reach the database as a negative offset (a 500), and a huge page size read a whole
/// history in one request.
/// </summary>
public static class Paging
{
    public const int MaxPageSize = 100;

    /// <summary>A validation error naming each bound broken, or null when the page can be read.</summary>
    public static ValidationError? Check(int page, int pageSize)
    {
        List<(string Code, string Message)> errors = [];
        if (page < 1)
            errors.Add(("InvalidPage", "Page must be 1 or more."));
        if (pageSize is < 1 or > MaxPageSize)
            errors.Add(("InvalidPageSize", "Page size must be between 1 and 100."));
        return errors.Count == 0 ? null : new ValidationError(ErrorTypes.ValidationError, "Validation failed for paging.", errors);
    }
}
