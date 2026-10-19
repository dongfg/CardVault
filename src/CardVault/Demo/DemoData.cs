using SkiaSharp;
using CardVault.Storage;

namespace CardVault.Demo;

internal static class DemoData
{
    public static void Prepare(CardStore store)
    {
        if (File.Exists(store.Database)) return;
        store.Initialize(); store.Load();
        var folder = Path.GetDirectoryName(store.Database)!;
        var temporary = new List<string>();
        string Picture(int theme, bool back = false)
        {
            using var bitmap = new SKBitmap(960, 604); using var canvas = new SKCanvas(bitmap);
            canvas.Clear(SKColor.Parse(new[] { "#174E46", "#D9CBB4", "#344966" }[theme]));
            using var paint = new SKPaint { IsAntialias = true, Color = new SKColor(255,255,255,18) };
            canvas.DrawCircle(810, 210, 350, paint); canvas.DrawCircle(830, 220, 250, paint);
            paint.Style = SKPaintStyle.Stroke; paint.StrokeWidth = 2;
            for (var i = 0; i < 8; i++) canvas.DrawCircle(750, 450, 200 + i * 24, paint);
            paint.Style = SKPaintStyle.Fill; paint.Color = theme == 1 ? SKColor.Parse("#493F32") : SKColor.Parse("#F0EFE4");
            using var font = new SKFont(SKTypeface.Default, 20);
            canvas.DrawText("C A R D V A U L T   /   S A M P L E", 60, 70, font, paint);
            if (back)
            {
                var color = paint.Color; paint.Color = SKColor.Parse("#172F2B"); canvas.DrawRect(0,140,960,110,paint); paint.Color = color;
                font.Size = 23; canvas.DrawText("DEMO CARD  /  NOT VALID FOR USE",60,335,font,paint);
                for (var i=0;i<66;i++) canvas.DrawRect(60+i*5,390,i%3==0?3:1,65,paint);
            }
            else
            {
                font.Size = 52; canvas.DrawText(new[] { "URBAN PASS", "THE READING CLUB", "EVERYDAY" }[theme],60,280,font,paint);
                font.Size = 21; canvas.DrawText(new[] { "A little closer to the city.", "A world between the pages.", "Small moments. Beautiful days." }[theme],60,325,font,paint);
                font.Size = 25; canvas.DrawText(new[] { "DEMO  0000  4826", "DEMO  0000  1650", "DEMO  0000  2098" }[theme],60,525,font,paint);
                font.Size = 16; canvas.DrawText("SAMPLE",810,535,font,paint);
            }
            var path = Path.Combine(folder, $"sample-{Guid.NewGuid()}.png");
            using var data = bitmap.Encode(SKEncodedImageFormat.Png,100); using (var stream = File.Create(path)) data.SaveTo(stream);
            temporary.Add(path); return path;
        }
        try { store.Save([new("demo-transit","城市交通卡","DEMO 0000 4826","日常通勤，让每次出发都从容一点。",Picture(0),Picture(0,true)),
            new("demo-library","图书馆借阅卡","DEMO 0000 1650","周末的阅读时光",Picture(1)),
            new("demo-member","日常会员卡","DEMO 0000 2098","收好生活里的小小便利",Picture(2))]); }
        finally { foreach (var path in temporary) File.Delete(path); }
    }
}
