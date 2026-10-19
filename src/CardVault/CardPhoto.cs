using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;

namespace CardVault;

// Each visible photo owns its decoded bitmap. Detaching a page or changing sides
// releases native memory; late background decodes are disposed instead of displayed.
internal sealed class CardPhoto : Border
{
    private Bitmap? bitmap;
    private int generation;
    public CardPhoto(CardStore store, string? path, string name, double radius)
    {
        Background = Brush.Parse("#E8F2EE"); CornerRadius = new CornerRadius(radius); ClipToBounds = true;
        void Label(string text) => Child = new TextBlock { Text = text, FontSize = 16, Foreground = Brush.Parse("#5E716D"),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(20) };
        Label(path == null ? "▱\n" + name : "正在读取图片…");
        AttachedToVisualTree += async (_, _) =>
        {
            var current = ++generation;
            if (path == null) return;
            try
            {
                var decoded = await Task.Run(() => ImageFiles.Decode(store.ImageBytes(path), 800));
                if (current != generation) { decoded.Dispose(); return; }
                bitmap = decoded; Child = new Image { Source = bitmap, Stretch = Stretch.Uniform };
            }
            catch { if (current == generation) Label("图片无法读取"); }
        };
        DetachedFromVisualTree += (_, _) => { generation++; Child = null; bitmap?.Dispose(); bitmap = null; Label(path == null ? "▱\n" + name : "正在读取图片…"); };
    }
}
