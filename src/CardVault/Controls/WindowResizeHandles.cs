using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;

namespace CardVault.Controls;

internal static class WindowResizeHandles
{
    public static void Attach(Window window, Panel host)
    {
        void Grip(WindowEdge edge, HorizontalAlignment horizontal, VerticalAlignment vertical, double width, double height, StandardCursorType cursor)
        {
            var grip = new Border { HorizontalAlignment = horizontal, VerticalAlignment = vertical,
                Width = width, Height = height, Background = Brushes.Transparent, Cursor = new Cursor(cursor) };
            grip.PointerPressed += (_, e) =>
            {
                if (window.WindowState == WindowState.Normal && e.GetCurrentPoint(window).Properties.IsLeftButtonPressed)
                { window.BeginResizeDrag(edge, e); e.Handled = true; }
            };
            host.Children.Add(grip);
            void Update() => grip.IsVisible = window.WindowState == WindowState.Normal && window.CanResize;
            window.PropertyChanged += (_, e) => { if (e.Property == Window.WindowStateProperty || e.Property == Window.CanResizeProperty) Update(); };
            Update();
        }
        Grip(WindowEdge.West, HorizontalAlignment.Left, VerticalAlignment.Stretch, 4, double.NaN, StandardCursorType.SizeWestEast);
        Grip(WindowEdge.East, HorizontalAlignment.Right, VerticalAlignment.Stretch, 4, double.NaN, StandardCursorType.SizeWestEast);
        Grip(WindowEdge.North, HorizontalAlignment.Stretch, VerticalAlignment.Top, double.NaN, 4, StandardCursorType.SizeNorthSouth);
        Grip(WindowEdge.South, HorizontalAlignment.Stretch, VerticalAlignment.Bottom, double.NaN, 4, StandardCursorType.SizeNorthSouth);
        Grip(WindowEdge.NorthWest, HorizontalAlignment.Left, VerticalAlignment.Top, 10, 10, StandardCursorType.TopLeftCorner);
        Grip(WindowEdge.NorthEast, HorizontalAlignment.Right, VerticalAlignment.Top, 10, 10, StandardCursorType.TopRightCorner);
        Grip(WindowEdge.SouthWest, HorizontalAlignment.Left, VerticalAlignment.Bottom, 10, 10, StandardCursorType.BottomLeftCorner);
        Grip(WindowEdge.SouthEast, HorizontalAlignment.Right, VerticalAlignment.Bottom, 10, 10, StandardCursorType.BottomRightCorner);
    }
}
