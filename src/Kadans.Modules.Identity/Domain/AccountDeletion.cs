namespace Kadans.Modules.Identity.Domain;

/// <summary>
/// An account its owner asked to delete. It is closed at once (signed out everywhere, no sign-in except to keep it)
/// and erased with everything in it at <see cref="EraseAfter"/>, unless kept before then. After the erasure the row
/// stays, without anything personal, as a record that a restored backup must not bring the account back; retention
/// removes it 30 days later. No foreign key: the user row is gone by then.
/// </summary>
internal sealed class AccountDeletion
{
    public required string UserId { get; init; }
    public DateTimeOffset RequestedAt { get; init; }
    public DateTimeOffset EraseAfter { get; init; }
    public DateTimeOffset? ErasedAt { get; set; }
}
