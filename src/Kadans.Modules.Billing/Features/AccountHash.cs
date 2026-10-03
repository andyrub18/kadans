using System.Security.Cryptography;
using System.Text;

namespace Kadans.Modules.Billing.Features;

/// <summary>
/// What a purchase carries to name its account (Google's obfuscated account id, at most 64 characters): a hash, so the
/// store never sees the id itself. A purchase whose hash is another account's is refused.
/// </summary>
internal static class AccountHash
{
    public static string Of(string userId) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes($"kadans-account:{userId}")));
}
