using System.IO.Compression;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Security;
using Kadans.SharedKernel.Users;
using OneOf;

namespace Kadans.Modules.Identity.Features.Account;

/// <summary>The archive handed to the person: its name and its bytes.</summary>
internal sealed record DataArchive(string FileName, byte[] Content);

/// <summary>
/// "Export my data" (ARCHITECTURE → "Statements in, data out"): what every module holds for the person
/// (<see cref="IUserDataExporter"/>), in one zip they can keep and open with a spreadsheet and a calendar app. Free for
/// every account, with or without a subscription: the data is theirs. Built in memory for the one request; nothing of
/// it is kept on the server, and the log says only that an export was made.
/// </summary>
internal sealed class DataExport(IEnumerable<IUserDataExporter> exporters, ICurrentUserService currentUser, ILogger<DataExport> logger)
{
    public async Task<OneOf<ApplicationError, DataArchive>> Build(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var userId = currentUser.UserId;
        if (string.IsNullOrWhiteSpace(userId))
            return new ApplicationError(ErrorTypes.Unauthorized, "Unable to resolve current user.");

        using var buffer = new MemoryStream();
        var files = 0;
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var exporter in exporters)
            {
                foreach (var file in await exporter.ExportAsync(userId, cancellationToken))
                {
                    var entry = zip.CreateEntry(file.Name, CompressionLevel.Optimal);
                    entry.LastWriteTime = now;
                    await using var content = entry.Open();
                    await content.WriteAsync(file.Content, cancellationToken);
                    files++;
                }
            }
        }
        logger.LogInformation("User {UserId} exported their data ({Files} files, {Bytes} bytes)", userId, files, buffer.Length);
        return new DataArchive($"kadans-export-{now.UtcDateTime:yyyy-MM-dd}.zip", buffer.ToArray());
    }
}
