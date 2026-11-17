namespace Kadans.SharedKernel.Users;

/// <summary>One file of a person's export: its path inside the archive ("budget/movements.csv") and its bytes.</summary>
public sealed record ExportedFile(string Name, byte[] Content);

/// <summary>
/// What a module holds for one person, as files they can keep and open elsewhere ("Export my data", ARCHITECTURE →
/// "Statements in, data out"). Each module writes its own; Identity gathers them into one archive. The mirror of
/// <see cref="IUserDataEraser"/>: what can be erased can be taken away first. Free for every account: a person's data
/// is theirs with or without a subscription.
/// </summary>
public interface IUserDataExporter
{
    Task<IReadOnlyList<ExportedFile>> ExportAsync(string userId, CancellationToken cancellationToken = default);
}
