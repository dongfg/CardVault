namespace CardVault.Storage;

public sealed class MissingDatabaseException(string location) : Exception("尚未找到卡册\n" + location);
