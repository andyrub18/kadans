using Kadans.SharedKernel.Errors;
using OneOf;
using OneOf.Types;

namespace Kadans.Modules.Budget.Domain;

/// <summary>
/// Gourdes first, then the money that actually reaches Haitian wallets: US dollars, euros and
/// Canadian dollars (diaspora), Dominican and Mexican pesos (border and trade). Currencies
/// never mix implicitly — an exchange is always an explicit pair of amounts.
/// </summary>
public enum Currency
{
    Htg,
    Usd,
    Eur,
    Cad,
    Dop,
    Mxn,
}

/// <summary>
/// An exact amount in one currency. Arithmetic across currencies is a programming error and
/// throws — services must group by currency before summing.
/// </summary>
internal readonly record struct Money(decimal Amount, Currency Currency)
{
    public static Money Zero(Currency currency) => new(0m, currency);

    public static Money operator +(Money left, Money right)
    {
        GuardSameCurrency(left, right);
        return left with { Amount = left.Amount + right.Amount };
    }

    public static Money operator -(Money left, Money right)
    {
        GuardSameCurrency(left, right);
        return left with { Amount = left.Amount - right.Amount };
    }

    public static Money operator -(Money money) => money with { Amount = -money.Amount };

    private static void GuardSameCurrency(Money left, Money right)
    {
        if (left.Currency != right.Currency)
            throw new InvalidOperationException($"Cannot combine {left.Currency} and {right.Currency}.");
    }

    /// <summary>Positive, at most two decimals, and within reason — every user-supplied amount goes through here.</summary>
    public static OneOf<ApplicationError, Success> ValidateAmount(decimal amount)
    {
        if (amount <= 0)
            return new ApplicationError(ErrorTypes.InvalidAmount, "Amount must be greater than zero.");
        if (amount != decimal.Round(amount, 2))
            return new ApplicationError(ErrorTypes.InvalidAmount, "Amount can have at most two decimals.");
        if (amount > 1_000_000_000_000m)
            return new ApplicationError(ErrorTypes.InvalidAmount, "Amount is out of range.");
        return new Success();
    }

    public override string ToString() => $"{Amount:0.00} {Currency.ToString().ToUpperInvariant()}";
}
