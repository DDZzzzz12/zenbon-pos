# Application Icons

Place your application icons in this directory for packaging.

## Required Icon Files

### Linux (Debian package)
- **File**: `icon.png`
- **Format**: PNG
- **Size**: 512x512 pixels or larger
- **Recommended**: 1024x1024 for best quality

### Windows (MSI installer)
- **File**: `icon.ico`
- **Format**: ICO (multi-resolution)
- **Sizes**: Include 16x16, 32x32, 48x48, 64x64, 128x128, 256x256
- **Tool**: Use online converter or ImageMagick

### macOS (DMG package)
- **File**: `icon.icns`
- **Format**: ICNS (Apple icon format)
- **Sizes**: Multiple resolutions from 16x16 to 1024x1024
- **Tool**: Use iconutil or online converter

## Creating Icons

### From PNG (Linux)
Already have a PNG? You're good to go! Just name it `icon.png`.

### Convert PNG to ICO (Windows)
```bash
# Using ImageMagick
convert icon.png -define icon:auto-resize=256,128,64,48,32,16 icon.ico
```

Or use online tool: https://convertio.co/png-ico/

### Convert PNG to ICNS (macOS)
```bash
# Create iconset directory
mkdir icon.iconset
cd icon.iconset

# Generate required sizes
sips -z 16 16     icon.png --out icon_16x16.png
sips -z 32 32     icon.png --out icon_16x16@2x.png
sips -z 32 32     icon.png --out icon_32x32.png
sips -z 64 64     icon.png --out icon_32x32@2x.png
sips -z 128 128   icon.png --out icon_128x128.png
sips -z 256 256   icon.png --out icon_128x128@2x.png
sips -z 256 256   icon.png --out icon_256x256.png
sips -z 512 512   icon.png --out icon_256x256@2x.png
sips -z 512 512   icon.png --out icon_512x512.png
sips -z 1024 1024 icon.png --out icon_512x512@2x.png

# Create ICNS file
iconutil -c icns icon.iconset
```

## Temporary Solution

If you don't have custom icons yet, the build will work without them (uses default Java icon).

To add later:
1. Create your icons
2. Place them in this directory
3. Rebuild the package

## Icon Design Guidelines

- **Simple and recognizable** at small sizes
- **High contrast** for visibility
- **Avoid text** (becomes unreadable at small sizes)
- **Use your brand colors**
- **Test at different sizes** (16x16 to 512x512)

## Current Status

❌ No icons present (using defaults)

Add icons here before final distribution for professional appearance!
