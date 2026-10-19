using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using CardVault.Controls;
using CardVault.Demo;
using CardVault.Imaging;
using CardVault.Models;
using CardVault.Storage;

namespace CardVault.Views;

public sealed class MainWindow : Window
{
    private static readonly IBrush Ink = Brush.Parse("#182B29"), Muted = Brush.Parse("#5E716D"), Pale = Brush.Parse("#E8F2EE"), Green = Brush.Parse("#176B5B");
    private readonly CardStore store;
    private List<Card> cards = [];
    private Card? selected, original, draft;
    private string page = "home", query = "";
    private bool busy, closing, missing, loaded;
    private readonly Grid body = new(), overlay = new();
    private readonly TextBlock status = new() { TextWrapping = TextWrapping.Wrap, Foreground = Muted, FontSize = 12, Margin = new Thickness(20, 4) };
    private readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private Button? saveButton;
    private Action? dismissOverlay;
    private bool Dirty => draft != null && draft != original;

    public MainWindow()
    {
        Title = "CardVault · Avalonia"; Width = 440; Height = 860; MinWidth = 360; MinHeight = 620;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        ExtendClientAreaToDecorationsHint = true;
        WindowDecorations = WindowDecorations.None;
        var iconUri = new Uri("avares://CardVault/Assets/cardvault.png");
        using (var stream = Avalonia.Platform.AssetLoader.Open(iconUri)) Icon = new WindowIcon(stream);
        var demo = Program.Arguments.Contains("--demo");
        store = new CardStore(CardStore.ResolvePath(Environment.GetEnvironmentVariable("CARDVAULT_DB_PATH"), demo));
        var root = new Grid { RowDefinitions = new RowDefinitions("48,*,Auto") };
        var titlebar = new Grid { ColumnDefinitions = new ColumnDefinitions("*,48,48,48"), Background = Brushes.Transparent };
        var drag = new Border { Background = Brushes.Transparent, Child = new TextBlock { Text = "CARDVAULT", FontSize = 10, LetterSpacing = 2, Foreground = Muted, Margin = new Thickness(20, 0), VerticalAlignment = VerticalAlignment.Center } };
        drag.PointerPressed += (_, e) => { if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) { if (e.ClickCount == 2) ToggleMax(); else BeginMoveDrag(e); } };
        titlebar.Children.Add(drag);
        AddTo(titlebar, Button("−", () => { WindowState = WindowState.Minimized; return Task.CompletedTask; }, "最小化"), 0, 1);
        AddTo(titlebar, Button("□", () => { ToggleMax(); return Task.CompletedTask; }, "最大化或还原"), 0, 2);
        AddTo(titlebar, Button("×", () => { Close(); return Task.CompletedTask; }, "关闭"), 0, 3);
        root.Children.Add(titlebar); AddTo(root, body, 1); AddTo(root, status, 2);
        var shell = new Grid(); shell.Children.Add(root); shell.Children.Add(overlay);
        overlay.IsVisible = false;
        Content = new Border { BorderBrush = Brush.Parse("#DFE6E1"), BorderThickness = new Thickness(1), Child = shell };
        toastTimer.Tick += (_, _) => { status.Text = ""; toastTimer.Stop(); };
        Opened += async (_, _) => await Run(async () =>
        {
            if (demo && Environment.GetEnvironmentVariable("CARDVAULT_DB_PATH") == null) await Task.Run(() => DemoData.Prepare(store));
            await Load();
        });
        Closing += (_, e) =>
        {
            if (closing) return;
            if (busy) { e.Cancel = true; return; }
            if (Dirty || overlay.IsVisible)
            {
                e.Cancel = true;
                if (overlay.IsVisible) dismissOverlay?.Invoke();
                else _ = Leave(() => { closing = true; Close(); });
            }
        };
        Closed += (_, _) => { toastTimer.Stop(); body.Children.Clear(); };
        KeyDown += (_, e) =>
        {
            if (e.Key != Key.Escape || busy) return;
            if (overlay.IsVisible) dismissOverlay?.Invoke();
            else if (page != "home") _ = Leave(Home);
            e.Handled = true;
        };
    }
    private void ToggleMax() => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    private void Message(string text) { status.Text = text; toastTimer.Stop(); toastTimer.Start(); }
    private async Task Run(Func<Task> action)
    {
        if (busy) return;
        busy = true; body.IsEnabled = false;
        try { await action(); }
        catch (Exception e) { Message(e.Message); }
        finally { busy = false; body.IsEnabled = !overlay.IsVisible; UpdateSave(); }
    }
    private async Task Load()
    {
        try { cards = await Task.Run(store.Load); loaded = true; missing = false; RenderHome(); }
        catch (MissingDatabaseException) { missing = true; loaded = false; RenderHome(); }
        catch (Exception e) { loaded = false; missing = false; RenderHome(e.Message); }
    }
    private static TextBlock Text(string text, double size = 14, IBrush? color = null) => new()
    { Text = text, FontSize = size, Foreground = color ?? Ink, TextWrapping = TextWrapping.Wrap };
    private static Button Button(string text, Func<Task> action, string? label = null, string? style = null)
    {
        var b = new Button { Content = text };
        if (style != null) b.Classes.Add(style);
        AutomationProperties.SetName(b, label ?? text);
        b.Click += async (_, _) => await action(); return b;
    }
    private static void AddTo(Grid grid, Control control, int row = 0, int col = 0)
    { Grid.SetRow(control, row); Grid.SetColumn(control, col); grid.Children.Add(control); }
    private static StackPanel Stack(double spacing = 16) => new() { Spacing = spacing };
    private static Border Surface(Control child) => new() { Child = child, Background = Brushes.White, CornerRadius = new CornerRadius(14), BorderBrush = Brush.Parse("#DFE6E1"), BorderThickness = new Thickness(1), Padding = new Thickness(16) };
    private void Reset()
    {
        body.Children.Clear(); saveButton = null;
    }
    private Grid PageGrid()
    {
        var g = new Grid { MaxWidth = 480, HorizontalAlignment = HorizontalAlignment.Stretch, RowDefinitions = new RowDefinitions("64,*") };
        body.Children.Add(g); return g;
    }
    private Control Bar(string title, string left, Func<Task> back, string right, Func<Task> action)
    {
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("80,*,80"), Margin = new Thickness(8, 0) };
        AddTo(g, Button(left, back));
        var label = Text(title, 17); label.FontWeight = FontWeight.SemiBold; label.HorizontalAlignment = HorizontalAlignment.Center; label.VerticalAlignment = VerticalAlignment.Center;
        AddTo(g, label, 0, 1);
        var b = Button(right, action); AddTo(g, b, 0, 2); if (right == "保存") saveButton = b;
        return g;
    }
    private static ScrollViewer Scroll(Control child) => new() { Content = child, HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled };
    private void Home() { draft = original = selected = null; page = "home"; RenderHome(); }
    private void RenderHome(string? error = null)
    {
        Reset(); page = "home";
        var g = new Grid { MaxWidth = 480, RowDefinitions = new RowDefinitions("Auto,*") };
        body.Children.Add(g);
        var header = Stack(16); header.Margin = new Thickness(20, 12, 20, 8);
        var brand = Text("▱  CardVault", 24); brand.FontWeight = FontWeight.SemiBold; header.Children.Add(brand);
        var search = new TextBox { PlaceholderText = "⌕  搜索名称、卡号或备注", Text = query, Height = 52 };
        AutomationProperties.SetName(search, "搜索名称、卡号或备注"); header.Children.Add(search); g.Children.Add(header);
        var listArea = new Grid(); AddTo(g, listArea, 1);
        var list = Stack(24); list.Margin = new Thickness(20, 12, 20, 100);
        var scroll = Scroll(list); listArea.Children.Add(scroll);
        void Populate()
        {
            list.Children.Clear();
            if (!loaded)
            {
                var empty = Stack(20); empty.Margin = new Thickness(8, 65, 8, 0);
                empty.Children.Add(Text("▱", 60, Green));
                empty.Children.Add(Text(missing ? "尚未找到卡册" : "暂时无法读取卡片", 20));
                empty.Children.Add(Text(missing ? store.Database + "\n\n已有卡册请等待同步完成后重试。首次使用可创建新卡册。" : error ?? "请检查数据库是否已同步到本机，以及所在目录是否可写", 13, Muted));
                if (missing) empty.Children.Add(Button("创建新卡册", () => Run(async () => { await Task.Run(store.Initialize); await Load(); }), style: "primary"));
                empty.Children.Add(Button("重新读取", () => Run(Load))); list.Children.Add(empty); return;
            }
            foreach (var card in cards.Where(c => c.Matches(query)))
            {
                var tile = new Grid();
                if (card.Front != null && card.Back != null)
                {
                    var rear = Photo(card.Back, card.Name, 24); rear.Margin = new Thickness(10, 0, 10, 8); rear.Opacity = .65;
                    rear.RenderTransform = new RotateTransform(-1); tile.Children.Add(rear);
                }
                var cover = Photo(card.Front ?? card.Back, card.Name, 24); cover.Margin = new Thickness(0, card.Front != null && card.Back != null ? 10 : 0, 0, 0);
                tile.Children.Add(cover);
                var caption = Text(card.Name, 16, Brushes.White); caption.FontWeight = FontWeight.SemiBold;
                var shade = new Border { Child = caption, Padding = new Thickness(18, 28, 18, 14), CornerRadius = new CornerRadius(0, 0, 24, 24), VerticalAlignment = VerticalAlignment.Bottom,
                    Background = new LinearGradientBrush { StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative), EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative), GradientStops = [new GradientStop(Colors.Transparent, 0), new GradientStop(Color.Parse("#B3101B19"), 1)] } };
                tile.Children.Add(shade);
                if (card.Front == null && card.Back != null) tile.Children.Add(new Border { Child = Text("反面", 11, Brushes.White), Background = Brush.Parse("#99101B19"), CornerRadius = new CornerRadius(8), Padding = new Thickness(8,4), Margin = new Thickness(14), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top });
                var button = Button("", () => { selected = card; Detail(); return Task.CompletedTask; }, card.Name, "card");
                button.Content = tile; list.Children.Add(button);
            }
            if (list.Children.Count == 0)
            {
                var empty = Stack(); empty.Margin = new Thickness(8, 80, 8, 0); empty.Children.Add(Text("▱", 60, Green));
                empty.Children.Add(Text(cards.Count == 0 ? "把常用卡片收进来" : "没有找到相关卡片", 20));
                empty.Children.Add(Text(cards.Count == 0 ? "保存正反面、卡号和备注，随时查找" : "换个关键词试试", 14, Muted)); list.Children.Add(empty);
            }
        }
        search.TextChanged += (_, _) => { query = search.Text ?? ""; Populate(); scroll.Offset = default; };
        Populate();
        if (loaded)
        {
            var add = Button("＋", () => { Edit(null); return Task.CompletedTask; }, "添加卡片", "primary");
            add.Width = add.Height = 56; add.FontSize = 28; add.CornerRadius = new CornerRadius(28);
            add.HorizontalAlignment = HorizontalAlignment.Right; add.VerticalAlignment = VerticalAlignment.Bottom; add.Margin = new Thickness(20);
            listArea.Children.Add(add);
        }
    }
    private Control Photo(string? path, string name, double radius = 12)
    {
        var ratio = new CardFrame { Child = new CardPhoto(store, path, name, radius) };
        AutomationProperties.SetName(ratio, name); return ratio;
    }
    private void Detail()
    {
        if (selected is not { } card) return;
        Reset(); page = "detail"; var g = PageGrid();
        g.Children.Add(Bar("", "‹ 返回", () => { Home(); return Task.CompletedTask; }, "编辑", () => { Edit(card); return Task.CompletedTask; }));
        var s = Stack(16); s.Margin = new Thickness(20, 0, 20, 32); AddTo(g, Scroll(s), 1);
        var name = Text(card.Name, 26); name.FontWeight = FontWeight.SemiBold; s.Children.Add(name);
        var sides = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*"), Background = Pale };
        var image = new ContentControl(); var hint = Text("", 12, Muted); hint.HorizontalAlignment = HorizontalAlignment.Center;
        void Show(bool front)
        {
            var path = front ? card.Front : card.Back;
            var picture = Photo(path, card.Name);
            if (path != null) picture.PointerPressed += (_, e) => { if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) Preview(path); };
            image.Content = picture; hint.Text = path == null ? "编辑以添加" + (front ? "正面" : "反面") : "点击图片查看大图";
            foreach (var c in sides.Children.OfType<Button>()) c.Background = Brushes.Transparent;
            ((Button)sides.Children[front ? 0 : 1]).Background = Brushes.White;
        }
        var frontButton = Button("正面" + (card.Front == null ? " · 未添加" : ""), () => { Show(true); return Task.CompletedTask; });
        var backButton = Button("反面" + (card.Back == null ? " · 未添加" : ""), () => { Show(false); return Task.CompletedTask; });
        frontButton.HorizontalAlignment = backButton.HorizontalAlignment = HorizontalAlignment.Stretch;
        frontButton.Height = backButton.Height = 44;
        AddTo(sides, frontButton); AddTo(sides, backButton, 0, 1);
        s.Children.Add(sides); s.Children.Add(image); s.Children.Add(hint); Show(card.Front != null || card.Back == null);
        s.Children.Add(Text("卡号", 14, Muted));
        var number = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto") };
        var numberText = Text(string.IsNullOrWhiteSpace(card.Number) ? "未填写卡号" : card.MaskedNumber, 16); numberText.VerticalAlignment = VerticalAlignment.Center; number.Children.Add(numberText);
        if (!string.IsNullOrWhiteSpace(card.Number))
        {
            bool revealed = false;
            var reveal = Button("显示", () => Task.CompletedTask);
            reveal.Click += (_, _) => { revealed = !revealed; reveal.Content = revealed ? "隐藏" : "显示"; numberText.Text = revealed ? card.Number : card.MaskedNumber; };
            AddTo(number, reveal, 0, 1);
            AddTo(number, Button("复制", () => Run(async () => { var clipboard = Clipboard ?? throw new IOException("剪贴板不可用"); await clipboard.SetTextAsync(card.Number); Message("卡号已复制"); })), 0, 2);
        }
        s.Children.Add(Surface(number)); s.Children.Add(Text("备注", 14, Muted)); s.Children.Add(Surface(Text(string.IsNullOrWhiteSpace(card.Note) ? "暂无备注" : card.Note, 15)));
    }
    private void Edit(Card? card)
    {
        original = card ?? new Card(Guid.NewGuid().ToString()); draft = original; RenderEdit();
    }
    private void RenderEdit()
    {
        if (draft is not { } card) return;
        Reset(); page = "edit"; var g = PageGrid(); bool existing = cards.Any(x => x.Id == card.Id);
        g.Children.Add(Bar(existing ? "编辑卡片" : "添加卡片", "取消", () => Leave(() => { if (selected == null) Home(); else Detail(); }), "保存", Save));
        var s = Stack(20); s.Margin = new Thickness(20, 0, 20, 32); AddTo(g, Scroll(s), 1);
        var images = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*"), ColumnSpacing = 12 };
        for (var i = 0; i < 2; i++)
        {
            bool front = i == 0; var path = front ? card.Front : card.Back; var side = front ? "正面" : "反面";
            var group = Stack(8); group.Children.Add(Text(side, 14, Muted));
            var photo = Button("", () => path == null ? Pick(front) : ShowPreview(path), side + "照片", "card");
            photo.Content = Photo(path, "＋ 添加照片"); group.Children.Add(photo);
            group.Children.Add(Button(path == null ? "添加照片" : "替换照片", () => Pick(front)));
            if (path != null) group.Children.Add(Button("移除", () => { draft = front ? draft! with { Front = null } : draft! with { Back = null }; RenderEdit(); return Task.CompletedTask; }));
            AddTo(images, group, 0, i);
        }
        s.Children.Add(images);
        var validation = Text("", 12, Brush.Parse("#B43D3D"));
        void Field(string label, string value, string placeholder, Action<string> change, bool multiline = false)
        {
            var group = Stack(10); group.Children.Add(Text(label, 14, Muted));
            var box = new TextBox { Text = value, PlaceholderText = placeholder, AcceptsReturn = multiline, TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap, MinHeight = multiline ? 112 : 48 };
            AutomationProperties.SetName(box, label);
            box.TextChanged += (_, _) => { change(box.Text ?? ""); ValidateDraft(validation); };
            group.Children.Add(box); s.Children.Add(group);
        }
        Field("名称 *", card.Name, "给卡片起个名字", v => draft = draft! with { Name = v });
        Field("卡号", card.Number, "输入卡号", v => draft = draft! with { Number = v });
        Field("备注", card.Note, "记录用途或其他补充信息", v => draft = draft! with { Note = v }, true);
        s.Children.Add(validation); ValidateDraft(validation);
        if (existing) s.Children.Add(Button("删除卡片", async () =>
        {
            if (await Confirm("删除「" + draft!.Name + "」？", "这张卡片的正反面图片、卡号和备注将一并删除，无法撤销。", "删除"))
                await Run(async () => { cards = await Task.Run(() => store.Save(cards.Where(x => x.Id != card.Id).ToList())); Home(); Message("卡片已删除"); });
        }, style: "danger"));
    }
    private bool Valid => draft != null && !string.IsNullOrWhiteSpace(draft.Name) && draft.Name.Length <= 40 && draft.Number.Length <= 80 && draft.Note.Length <= 2000;
    private void UpdateSave() { if (saveButton != null) saveButton.IsEnabled = Valid && Dirty && !busy; }
    private void ValidateDraft(TextBlock validation)
    {
        validation.Text = draft!.Name.Length > 40 ? "名称最多 40 字" : draft.Number.Length > 80 ? "卡号最多 80 字符" : draft.Note.Length > 2000 ? "备注最多 2000 字符" : string.IsNullOrWhiteSpace(draft.Name) ? "请填写卡片名称" : "";
        UpdateSave();
    }
    private Task Save() => Run(async () =>
    {
        if (!Valid || !Dirty) return;
        var card = draft! with { Name = draft!.Name.Trim(), Number = draft.Number.Trim() };
        var next = cards.Any(x => x.Id == card.Id) ? cards.Select(x => x.Id == card.Id ? card : x).ToList() : new[] { card }.Concat(cards).ToList();
        cards = await Task.Run(() => store.Save(next)); selected = cards.First(x => x.Id == card.Id);
        draft = original = null; Detail(); Message("卡片已保存");
    });
    private Task Pick(bool front) => Run(async () =>
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions { Title = "选择卡片照片", AllowMultiple = false,
            FileTypeFilter = [new FilePickerFileType("卡片照片") { Patterns = ["*.jpg", "*.jpeg", "*.png", "*.webp"], MimeTypes = ["image/jpeg", "image/png", "image/webp"] }] });
        if (files.Count == 0) return;
        var path = files[0].TryGetLocalPath() ?? throw new IOException("请选择已下载到本机的图片");
        if (!new[] { ".jpg", ".jpeg", ".png", ".webp" }.Contains(Path.GetExtension(path).ToLowerInvariant())) throw new IOException("请选择 JPEG、PNG 或 WebP 图片");
        await Task.Run(() => ImageFiles.Validate(store.ImageBytes(path)));
        draft = front ? draft! with { Front = path } : draft! with { Back = path }; RenderEdit();
    });
    private Task ShowPreview(string path) { Preview(path); return Task.CompletedTask; }
    private void Preview(string path)
    {
        if (overlay.IsVisible) return;
        Bitmap bitmap;
        try { bitmap = ImageFiles.Decode(store.ImageBytes(path)); }
        catch (Exception e) { Message(e.Message); return; }
        var root = new Grid { Background = Brush.Parse("#101B19"), ClipToBounds = true };
        var transform = new TransformGroup(); var scale = new ScaleTransform(1, 1); var pan = new TranslateTransform(); transform.Children.Add(scale); transform.Children.Add(pan);
        var image = new Image { Source = bitmap, Stretch = Stretch.Uniform, Margin = new Thickness(24), RenderTransform = transform, RenderTransformOrigin = new RelativePoint(.5, .5, RelativeUnit.Relative) };
        var viewport = new Border { Child = image, ClipToBounds = true, Background = Brushes.Transparent }; root.Children.Add(viewport);
        Point? point = null;
        void Zoom(double factor) { scale.ScaleX = scale.ScaleY = Math.Clamp(scale.ScaleX * factor, 1, 5); Clamp(); }
        void Clamp()
        {
            if (scale.ScaleX == 1) { pan.X = pan.Y = 0; return; }
            var maxX = viewport.Bounds.Width * (scale.ScaleX - 1) / 2;
            var maxY = viewport.Bounds.Height * (scale.ScaleX - 1) / 2;
            pan.X = Math.Clamp(pan.X, -maxX, maxX); pan.Y = Math.Clamp(pan.Y, -maxY, maxY);
        }
        viewport.PointerWheelChanged += (_, e) => { Zoom(Math.Pow(1.15, e.Delta.Y)); e.Handled = true; };
        viewport.PointerPressed += (_, e) => { if (e.GetCurrentPoint(viewport).Properties.IsLeftButtonPressed) { point = e.GetPosition(viewport); e.Pointer.Capture(viewport); } };
        viewport.PointerMoved += (_, e) => { if (point is { } p) { var current = e.GetPosition(viewport); pan.X += current.X - p.X; pan.Y += current.Y - p.Y; point = current; Clamp(); } };
        viewport.PointerReleased += (_, e) => { point = null; e.Pointer.Capture(null); };
        viewport.PointerCaptureLost += (_, _) => point = null;
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(12), Background = Brushes.White };
        buttons.Children.Add(Button("缩小", () => { Zoom(1 / 1.5); return Task.CompletedTask; }));
        buttons.Children.Add(Button("放大", () => { Zoom(1.5); return Task.CompletedTask; }));
        void ClosePreview() { HideOverlay(); bitmap.Dispose(); }
        var close = Button("关闭", () => { ClosePreview(); return Task.CompletedTask; });
        buttons.Children.Add(close);
        root.Children.Add(buttons); overlay.Children.Add(root); overlay.IsVisible = true; body.IsEnabled = false;
        dismissOverlay = ClosePreview; Dispatcher.UIThread.Post(() => close.Focus());
    }
    private void HideOverlay()
    {
        overlay.IsVisible = false; overlay.Children.Clear(); dismissOverlay = null; body.IsEnabled = !busy;
    }
    private async Task Leave(Action action)
    {
        if (busy) return;
        if (Dirty && !await Confirm("放弃未保存的修改？", "当前修改还没有保存。", "放弃修改")) return;
        draft = original = null; action();
    }
    private Task<bool> Confirm(string title, string description, string confirm)
    {
        if (overlay.IsVisible) return Task.FromResult(false);
        var completion = new TaskCompletionSource<bool>();
        var s = Stack(16); s.Children.Add(Text(title, 20)); s.Children.Add(Text(description, 14, Muted));
        void Finish(bool result) { HideOverlay(); completion.TrySetResult(result); }
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var cancel = Button("取消", () => { Finish(false); return Task.CompletedTask; }); buttons.Children.Add(cancel);
        buttons.Children.Add(Button(confirm, () => { Finish(true); return Task.CompletedTask; }, style: "danger")); s.Children.Add(buttons);
        var backdrop = new Grid { Background = Brush.Parse("#66101B19") };
        var dialog = Surface(s); dialog.MaxWidth = 360; dialog.Margin = new Thickness(20); dialog.HorizontalAlignment = HorizontalAlignment.Center; dialog.VerticalAlignment = VerticalAlignment.Center;
        backdrop.Children.Add(dialog); overlay.Children.Add(backdrop); overlay.IsVisible = true; body.IsEnabled = false;
        dismissOverlay = () => Finish(false); Dispatcher.UIThread.Post(() => cancel.Focus()); return completion.Task;
    }
}
