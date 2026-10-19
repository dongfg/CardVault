using Avalonia.Media.Imaging;
using SkiaSharp;

namespace CardVault;

internal static class ImageFiles
{
    public static void Validate(byte[] bytes)
    {
        using var data = SKData.CreateCopy(bytes);
        using var codec = SKCodec.Create(data) ?? throw new IOException("请选择有效的 JPEG、PNG 或 WebP 图片");
        if ((long)codec.Info.Width * codec.Info.Height > 40_000_000) throw new IOException("图片尺寸过大，请缩小后重试");
    }
    public static Bitmap Decode(byte[] bytes, int width = 1600)
    {
        Validate(bytes);
        using var stream = new MemoryStream(bytes);
        return Bitmap.DecodeToWidth(stream, width);
    }
}
