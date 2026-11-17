using Kadans.SharedKernel.Text;
using Kadans.SharedKernel.Users;

namespace Kadans.Modules.Identity.Features.Account;

/// <summary>
/// "Export my data": the account itself (ARCHITECTURE → "Statements in, data out"), as the person set it: the name
/// they sign in with, their address, the name they show, their time zone and language. Never a password's hash, a
/// token or a device: those are the service's workings, not what the person put in.
/// </summary>
internal sealed class AccountUserDataExporter(IUserDirectory users) : IUserDataExporter
{
    public async Task<IReadOnlyList<ExportedFile>> ExportAsync(string userId, CancellationToken cancellationToken = default)
    {
        var user = await users.FindAsync(userId, cancellationToken);
        if (user is null)
            return [];
        var file = new Csv("Username", "Email", "Name", "Time zone", "Language", "Clock");
        file.Row(user.Username, user.Email, user.DisplayName, user.TimeZoneId, user.Language, user.ClockFormat.ToString());
        return [new ExportedFile("account.csv", file.ToBytes())];
    }
}
