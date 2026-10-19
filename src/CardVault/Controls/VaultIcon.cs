using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace CardVault.Controls;

internal enum IconKind { Add, Search, Cards, Minimize, Maximize, Close, Back }

// Geometric icons have a fixed visual center, independent of font glyph metrics.
internal sealed class VaultIcon(IconKind kind, IBrush? brush = null) : Control
{
    public override void Render(DrawingContext context)
    {
        base.Render(context);
        var size = Math.Min(Bounds.Width, Bounds.Height);
        using var transform = context.PushTransform(Matrix.CreateScale(size / 24, size / 24)
            * Matrix.CreateTranslation((Bounds.Width - size) / 2, (Bounds.Height - size) / 2));
        var pen = new Pen(brush ?? Brush.Parse("#176B5B"), 1.8, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round);
        void Line(double x1, double y1, double x2, double y2) => context.DrawLine(pen, new Point(x1,y1), new Point(x2,y2));
        switch (kind)
        {
            case IconKind.Add: Line(5,12,19,12); Line(12,5,12,19); break;
            case IconKind.Minimize: Line(5,12,19,12); break;
            case IconKind.Maximize: context.DrawRectangle(null, pen, new Rect(5,5,14,14)); break;
            case IconKind.Close: Line(6,6,18,18); Line(18,6,6,18); break;
            case IconKind.Back: Line(14,5,7,12); Line(7,12,14,19); break;
            case IconKind.Search:
                context.DrawEllipse(null, pen, new Point(10,10), 6, 6); Line(14.5,14.5,20,20); break;
            case IconKind.Cards:
                context.DrawRectangle(null, pen, new Rect(7,4,14,12), 2, 2);
                context.DrawRectangle(Brush.Parse("#F7F8F5"), pen, new Rect(3,8,16,12), 2, 2);
                Line(3,12,19,12); break;
        }
    }
}
