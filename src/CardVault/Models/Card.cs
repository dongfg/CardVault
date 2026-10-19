namespace CardVault.Models;

public sealed record Card(string Id, string Name = "", string Number = "", string Note = "", string? Front = null, string? Back = null)
{
    public bool Matches(string query) => Name.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase)
        || Note.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase)
        || Compact(Number).Contains(Compact(query), StringComparison.OrdinalIgnoreCase);
    private static string Compact(string text) => string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
    public string MaskedNumber => Compact(Number) is var n && n.Length > 0 ? "••••  " + n[^Math.Min(4, n.Length - 1)..] : "";
}
