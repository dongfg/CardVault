using Avalonia;
using Avalonia.Controls;

namespace CardVault.Controls;

internal sealed class CardFrame : Decorator
{
    protected override Size MeasureOverride(Size availableSize)
    {
        var width = double.IsFinite(availableSize.Width) ? availableSize.Width : 400;
        var size = new Size(width, width / 1.586); Child?.Measure(size); return size;
    }
    protected override Size ArrangeOverride(Size finalSize) { Child?.Arrange(new Rect(finalSize)); return finalSize; }
}
