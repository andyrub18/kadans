namespace Kadans.SharedKernel.Users;

/// <summary>
/// Erases everything a module holds for one user, when their account is erased (7 days after they asked to delete
/// it). Each module implements it for its own tables; Identity calls them all, then removes the account itself. It
/// must be safe to run again: a run cut short is finished by the next.
/// </summary>
public interface IUserDataEraser
{
    Task EraseAsync(string userId, CancellationToken cancellationToken = default);
}
