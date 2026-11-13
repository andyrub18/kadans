using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Configuration;

namespace Kadans.SharedKernel.Security;

/// <summary>
/// A key for one purpose, derived (HKDF) from the server's one secret, <c>Jwt:Key</c>, which is never in the
/// database. What is signed with it cannot be forged from a database dump alone (the backups copied off the
/// server). Each purpose has its own key: one cannot stand in for another. Changing <c>Jwt:Key</c> changes them all.
/// </summary>
public static class ServerSecret
{
    public static byte[] DeriveKey(IConfiguration configuration, string purpose)
    {
        var serverKey = configuration["Jwt:Key"];
        return string.IsNullOrEmpty(serverKey) ? throw new InvalidOperationException("Jwt:Key is missing.") : DeriveKey(serverKey, purpose);
    }

    public static byte[] DeriveKey(string serverKey, string purpose) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, Encoding.UTF8.GetBytes(serverKey), outputLength: 32, info: Encoding.UTF8.GetBytes(purpose));
}
