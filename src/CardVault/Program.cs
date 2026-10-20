using Avalonia;

namespace CardVault;

internal static class Program
{
    public static string[] Arguments { get; private set; } = [];
    [STAThread]
    public static void Main(string[] args)
    {
        Arguments = args;
        AppBuilder.Configure<App>().UsePlatformDetect().LogToTrace().StartWithClassicDesktopLifetime(args);
    }
}
