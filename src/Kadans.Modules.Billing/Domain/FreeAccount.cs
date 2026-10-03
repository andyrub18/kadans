namespace Kadans.Modules.Billing.Domain;

/// <summary>
/// An account whose phones are free, with no store involved: the closed test's testers, the account Google's reviewers
/// sign in with, family. Added and removed by an admin (<c>tools/admin/free_accounts.py</c>).
/// </summary>
internal sealed class FreeAccount
{
    public required string UserId { get; init; }

    /// <summary>The admin who added it.</summary>
    public required string AddedBy { get; init; }

    public DateTimeOffset AddedAt { get; init; }
}
