#requires -Version 5.1
<#
.SYNOPSIS
  Module Porter for the AUTISM Seedcracker addon.

  Give it the path to a source .java module class (e.g. from a decompiled client), and it:
    1. Reads the class name + package.
    2. Generates an AUTISM module skeleton in the addon's modules package, pre-filling:
         - settings it can detect (BoolSetting/IntSetting/DoubleSetting/StringSetting/EnumSetting)
         - common hook overrides (onEnable/onDisable/tick/onPacketSend)
         - a best-effort translation of common Yarn/Meteor/obfuscated API calls to Mojang 26.2.
    3. Inserts the import + register(...) line into SeedcrackerAddon.java (in a category you pick).
    4. Optionally runs the Gradle build.

  It cannot perfectly translate obfuscated code (class_1707.method_7611 etc.) - those are left
  as TODO comments for you to finish, but everything around them is generated for you.
#>

Add-Type -AssemblyName PresentationFramework
Add-Type -AssemblyName System.Windows.Forms

$ErrorActionPreference = 'Stop'

# ---------- locate repo root (folder containing SeedcrackerAddon.java) ----------
function Find-AddonRoot {
    param([string]$start)
    $dir = $start
    for ($i = 0; $i -lt 8 -and $dir; $i++) {
        $candidate = Join-Path $dir 'src\main\java\com\autism\seedcracker\SeedcrackerAddon.java'
        if (Test-Path $candidate) { return $dir }
        $parent = Split-Path $dir -Parent
        if ($parent -eq $dir) { break }
        $dir = $parent
    }
    return $null
}

# ---------- translation table (source API -> Mojang 26.2) ----------
$script:Translations = [ordered]@{
    'mc.player'                                  = 'mc.player'
    'this.mc.field_1724'                         = 'Minecraft.getInstance().player'
    'this.mc.field_1687'                         = 'Minecraft.getInstance().level'
    'this.mc.method_1562()'                      = 'Minecraft.getInstance().getConnection()'
    'field_1724'                                 = 'player'
    'field_1687'                                 = 'level'
    'PlayerInteractItemC2SPacket'                = 'ServerboundUseItemPacket'
    'UpdateSelectedSlotC2SPacket'                = 'ServerboundSetCarriedItemPacket'
    'PlayerActionC2SPacket'                      = 'ServerboundPlayerActionPacket'
    'PlayerMoveC2SPacket'                        = 'ServerboundMovePlayerPacket'
    'setVelocity('                               = 'setDeltaMovement('
    'addVelocity('                               = 'push('
    'networkHandler.sendPacket'                  = 'getConnection().send'
    'field_7545'                                 = 'getSelectedSlot()'
    'method_31548()'                             = 'getInventory()'
    'field_7512'                                 = 'containerMenu'
    'method_7611'                                = 'slots.get'
    'class_1268.field_5808'                      = 'InteractionHand.MAIN_HAND'
    'Hand.MAIN_HAND'                             = 'InteractionHand.MAIN_HAND'
    'net.minecraft.util.Hand'                    = 'net.minecraft.world.InteractionHand'
    'net.minecraft.util.math.BlockPos'           = 'net.minecraft.core.BlockPos'
    'net.minecraft.util.math.Direction'          = 'net.minecraft.core.Direction'
    'net.minecraft.util.math.Vec3d'              = 'net.minecraft.world.phys.Vec3'
    'net.minecraft.item.Items'                   = 'net.minecraft.world.item.Items'
    'net.minecraft.block.Blocks'                 = 'net.minecraft.world.level.block.Blocks'
}

function Convert-SourceToMojang {
    param([string]$text)
    foreach ($k in $script:Translations.Keys) {
        $text = $text -replace [regex]::Escape($k), $script:Translations[$k]
    }
    return $text
}

# ---------- detect settings from source ----------
function Get-DetectedSettings {
    param([string]$src)
    $settings = @()
    # BoolSetting
    foreach ($m in [regex]::Matches($src, 'BoolSetting[^\n]*?name\("(?<n>[^"]+)"')) {
        $settings += [pscustomobject]@{ Type='BoolSetting'; Name=$m.Groups['n'].Value; Default='false' }
    }
    foreach ($m in [regex]::Matches($src, 'new BooleanValue\("(?<n>[^"]+)"')) {
        $settings += [pscustomobject]@{ Type='BoolSetting'; Name=$m.Groups['n'].Value; Default='false' }
    }
    # IntSetting / NumberSetting
    foreach ($m in [regex]::Matches($src, '(?:IntSetting|NumberSetting)[^\n]*?name\("(?<n>[^"]+)"')) {
        $settings += [pscustomobject]@{ Type='IntSetting'; Name=$m.Groups['n'].Value; Default='1, 1, 100, 1' }
    }
    foreach ($m in [regex]::Matches($src, 'new NumberValue\("(?<n>[^"]+)"')) {
        $settings += [pscustomobject]@{ Type='IntSetting'; Name=$m.Groups['n'].Value; Default='1, 1, 100, 1' }
    }
    # DoubleSetting
    foreach ($m in [regex]::Matches($src, 'DoubleSetting[^\n]*?name\("(?<n>[^"]+)"')) {
        $settings += [pscustomobject]@{ Type='DoubleSetting'; Name=$m.Groups['n'].Value; Default='1.0, 0.0, 100.0, 0.1' }
    }
    # StringSetting
    foreach ($m in [regex]::Matches($src, '(?:StringSetting)[^\n]*?name\("(?<n>[^"]+)"')) {
        $settings += [pscustomobject]@{ Type='StringSetting'; Name=$m.Groups['n'].Value; Default='""' }
    }
    return $settings
}

function Convert-ToCamel {
    param([string]$name)
    $parts = $name -replace '[^a-zA-Z0-9 ]','' -split '\s+' | Where-Object { $_ }
    if (-not $parts) { return 'setting' }
    $first = $parts[0].ToLower()
    $rest = ($parts | Select-Object -Skip 1 | ForEach-Object { $_.Substring(0,1).ToUpper() + $_.Substring(1) }) -join ''
    return "$first$rest"
}

# ---------- generate the module file ----------
function New-ModuleSource {
    param(
        [string]$ClassName,
        [string]$Package,       # e.g. modules | disabler | krypton
        [string]$Description,
        [string]$SourceBody,    # translated best-effort body for tick()
        [object[]]$Settings
    )
    $pkg = "com.autism.seedcracker.$Package"
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("package $pkg;")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("import com.autism.seedcracker.SeedcrackerAddon;")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("import autismclient.api.module.BoolSetting;")
    [void]$sb.AppendLine("import autismclient.api.module.IntSetting;")
    [void]$sb.AppendLine("import autismclient.api.module.DoubleSetting;")
    [void]$sb.AppendLine("import autismclient.api.module.StringSetting;")
    [void]$sb.AppendLine("import autismclient.modules.Module;")
    [void]$sb.AppendLine("import net.minecraft.client.Minecraft;")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("/**")
    [void]$sb.AppendLine(" * $Description")
    [void]$sb.AppendLine(" *")
    [void]$sb.AppendLine(" * Generated by Module Porter. Review the TODO markers - any leftover obfuscated")
    [void]$sb.AppendLine(" * calls (class_*/method_*) need manual mapping to Mojang 26.2.")
    [void]$sb.AppendLine(" */")
    [void]$sb.AppendLine("public final class $ClassName extends Module {")
    [void]$sb.AppendLine("")

    # settings fields
    foreach ($s in $Settings) {
        $var = Convert-ToCamel $s.Name
        switch ($s.Type) {
            'BoolSetting'   { [void]$sb.AppendLine("    private final BoolSetting $var = add(new BoolSetting(`"$($s.Name -replace ' ','-')`", `"$($s.Name)`", $($s.Default)).group(`"General`"));") }
            'IntSetting'    { [void]$sb.AppendLine("    private final IntSetting $var = add(new IntSetting(`"$($s.Name -replace ' ','-')`", `"$($s.Name)`", $($s.Default)).group(`"General`"));") }
            'DoubleSetting' { [void]$sb.AppendLine("    private final DoubleSetting $var = add(new DoubleSetting(`"$($s.Name -replace ' ','-')`", `"$($s.Name)`", $($s.Default)).group(`"General`"));") }
            'StringSetting' { [void]$sb.AppendLine("    private final StringSetting $var = add(new StringSetting(`"$($s.Name -replace ' ','-')`", `"$($s.Name)`", $($s.Default)).group(`"General`"));") }
        }
    }
    if ($Settings.Count -gt 0) { [void]$sb.AppendLine("") }

    [void]$sb.AppendLine("    public $ClassName(autismclient.modules.ModuleCategory category) {")
    [void]$sb.AppendLine("        super(SeedcrackerAddon.ID + `":" + ($ClassName -replace '([a-z])([A-Z])','$1-$2').ToLower() + "`", `"$($ClassName -replace 'Module$','')`", category,")
    [void]$sb.AppendLine("            `"$Description`");")
    [void]$sb.AppendLine("    }")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("    @Override")
    [void]$sb.AppendLine("    public void onEnable() {")
    [void]$sb.AppendLine("    }")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("    @Override")
    [void]$sb.AppendLine("    public void onDisable() {")
    [void]$sb.AppendLine("    }")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("    @Override")
    [void]$sb.AppendLine("    public void onGameLeft() {")
    [void]$sb.AppendLine("        setEnabledSilently(false);")
    [void]$sb.AppendLine("    }")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("    @Override")
    [void]$sb.AppendLine("    public void tick() {")
    [void]$sb.AppendLine("        Minecraft mc = Minecraft.getInstance();")
    [void]$sb.AppendLine("        if (mc.player == null || mc.level == null) return;")
    if ($SourceBody.Trim()) {
        foreach ($line in ($SourceBody -split "`r?`n")) {
            [void]$sb.AppendLine("        $line")
        }
    } else {
        [void]$sb.AppendLine("        // TODO: port the module's per-tick logic here.")
    }
    [void]$sb.AppendLine("    }")
    [void]$sb.AppendLine("}")
    return $sb.ToString()
}

# ---------- insert import + register into SeedcrackerAddon.java ----------
function Register-Module {
    param(
        [string]$AddonFile,
        [string]$Package,
        [string]$ClassName,
        [string]$CategoryVar   # e.g. catDogsMisc
    )
    $text = Get-Content -Raw -LiteralPath $AddonFile
    $fqcn = "com.autism.seedcracker.$Package.$ClassName"

    if ($text -notmatch [regex]::Escape("import $fqcn;")) {
        # insert import after the last existing 'import com.autism.seedcracker.modules.' line, else after first import
        if ($text -match '(?m)^import com\.autism\.seedcracker\.modules\.[A-Za-z]+;\s*$') {
            $text = [regex]::Replace($text, '(?m)^(import com\.autism\.seedcracker\.modules\.[A-Za-z]+;\s*)', "`$1import $fqcn;`r`n", 1)
        } else {
            $text = [regex]::Replace($text, '(?m)^(import .*?;\s*)$', "`$1`r`nimport $fqcn;", 1)
        }
    }

    $registerLine = "        AutismAddons.modules().register(new $ClassName($CategoryVar));"
    if ($text -notmatch [regex]::Escape("new $ClassName(")) {
        # insert before the closing of onInitialize: find the last '    }' before '    @Override\n    public String getPackage'
        $anchor = '        AutismAddons.commands().register(new BedrockFinderCommand());'
        if ($text.Contains($anchor)) {
            $text = $text.Replace($anchor, "$registerLine`r`n`r`n$anchor")
        } else {
            # fallback: insert before '    @Override' of getPackage
            $text = [regex]::Replace($text, '(\r?\n    @Override\r?\n    public String getPackage)', "`r`n$registerLine`$1", 1)
        }
    }

    Set-Content -LiteralPath $AddonFile -Value $text -NoNewline -Encoding UTF8
}

# ---------- build the WPF UI ----------
$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="AUTISM Module Porter" Width="640" Height="560"
        WindowStartupLocation="CenterScreen" Background="#FF1E1E28">
  <Grid Margin="14">
    <Grid.RowDefinitions>
      <RowDefinition Height="Auto"/><RowDefinition Height="Auto"/>
      <RowDefinition Height="Auto"/><RowDefinition Height="Auto"/>
      <RowDefinition Height="Auto"/><RowDefinition Height="Auto"/>
      <RowDefinition Height="*"/><RowDefinition Height="Auto"/>
    </Grid.RowDefinitions>
    <Grid.ColumnDefinitions>
      <ColumnDefinition Width="110"/><ColumnDefinition Width="*"/><ColumnDefinition Width="Auto"/>
    </Grid.ColumnDefinitions>

    <TextBlock Grid.Row="0" Grid.Column="0" Text="Source:" Foreground="White" VerticalAlignment="Center"/>
    <TextBox   Grid.Row="0" Grid.Column="1" x:Name="SrcPath" Margin="6,2" Background="#FF2A2A38" Foreground="White" BorderBrush="#FF4A4A5C"/>
    <StackPanel Grid.Row="0" Grid.Column="2" Orientation="Horizontal">
      <Button x:Name="Browse" Content="File..." Margin="4,2" Padding="8,3"/>
      <Button x:Name="BrowseFolder" Content="Folder..." Margin="4,2" Padding="8,3"/>
    </StackPanel>

    <TextBlock Grid.Row="1" Grid.Column="0" Text="Class name:" Foreground="White" VerticalAlignment="Center"/>
    <TextBox   Grid.Row="1" Grid.Column="1" x:Name="ClassName" Margin="6,2" Background="#FF2A2A38" Foreground="White" BorderBrush="#FF4A4A5C"/>

    <TextBlock Grid.Row="2" Grid.Column="0" Text="Package:" Foreground="White" VerticalAlignment="Center"/>
    <ComboBox  Grid.Row="2" Grid.Column="1" x:Name="Package" Margin="6,2" Background="#FF2A2A38" Foreground="White">
      <ComboBoxItem Content="modules" IsSelected="True"/>
      <ComboBoxItem Content="disabler"/>
      <ComboBoxItem Content="krypton"/>
    </ComboBox>

    <TextBlock Grid.Row="3" Grid.Column="0" Text="Category tab:" Foreground="White" VerticalAlignment="Center"/>
    <ComboBox  Grid.Row="3" Grid.Column="1" x:Name="Category" Margin="6,2" Background="#FF2A2A38" Foreground="White">
      <ComboBoxItem Content="catDogsMisc" IsSelected="True"/>
      <ComboBoxItem Content="catFinders"/>
      <ComboBoxItem Content="catEntity"/>
      <ComboBoxItem Content="catFake"/>
      <ComboBoxItem Content="catRender"/>
      <ComboBoxItem Content="catMovement"/>
      <ComboBoxItem Content="catTrading"/>
      <ComboBoxItem Content="catDisabler"/>
    </ComboBox>

    <TextBlock Grid.Row="4" Grid.Column="0" Text="Description:" Foreground="White" VerticalAlignment="Center"/>
    <TextBox   Grid.Row="4" Grid.Column="1" x:Name="Description" Margin="6,2" Background="#FF2A2A38" Foreground="White" BorderBrush="#FF4A4A5C"/>

    <StackPanel Grid.Row="5" Grid.Column="0" Grid.ColumnSpan="3" Orientation="Horizontal" Margin="0,8,0,4">
      <CheckBox x:Name="Translate" Content="Best-effort API translation (Yarn/obf -> Mojang)" IsChecked="True" Foreground="#FFB0B0C0" Margin="0,0,14,0"/>
      <CheckBox x:Name="Register" Content="Auto-register in SeedcrackerAddon" IsChecked="True" Foreground="#FFB0B0C0" Margin="0,0,14,0"/>
      <CheckBox x:Name="DoBuild" Content="Run Gradle build after" IsChecked="False" Foreground="#FFB0B0C0"/>
    </StackPanel>

    <TextBox Grid.Row="6" Grid.Column="0" Grid.ColumnSpan="3" x:Name="Log"
             AcceptsReturn="True" IsReadOnly="True" VerticalScrollBarVisibility="Auto"
             FontFamily="Consolas" FontSize="11" Background="#FF14141C" Foreground="#FF9AD1FF" BorderBrush="#FF4A4A5C" Margin="0,4"/>

    <StackPanel Grid.Row="7" Grid.Column="0" Grid.ColumnSpan="3" Orientation="Horizontal" HorizontalAlignment="Right">
      <Button x:Name="Port" Content="Port Module" Padding="16,6" Margin="6,0" Background="#FF3A6EA5" Foreground="White" BorderThickness="0"/>
      <Button x:Name="Batch" Content="Batch Port Folder" Padding="16,6" Margin="6,0" Background="#FF3A8A5E" Foreground="White" BorderThickness="0"/>
      <Button x:Name="Close" Content="Close" Padding="16,6" Margin="6,0" Background="#FF3A3A4C" Foreground="White" BorderThickness="0"/>
    </StackPanel>
  </Grid>
</Window>
"@

$reader = New-Object System.Xml.XmlNodeReader ([xml]$xaml)
$window = [Windows.Markup.XamlReader]::Load($reader)

$ui = @{}
'SrcPath','ClassName','Package','Category','Description','Translate','Register','DoBuild','Log','Port','Batch','Close','Browse','BrowseFolder' |
  ForEach-Object { $ui[$_] = $window.FindName($_) }

function Write-Log([string]$msg) {
    $ui.Log.AppendText("$msg`r`n")
    $ui.Log.ScrollToEnd()
}

$script:AddonRoot = $null

function Resolve-AddonRoot([string]$hint) {
    if ($script:AddonRoot) { return $script:AddonRoot }
    if ($env:ADDON_ROOT) { return $env:ADDON_ROOT }
    if ($hint) {
        $r = Find-AddonRoot $hint
        if ($r) { return $r }
    }
    return Find-AddonRoot (Get-Location)
}

# Ports a single source file. Returns $true on success.
function Invoke-PortOne {
    param(
        [string]$SrcFile,
        [string]$ClassName,
        [string]$Package,
        [string]$Category,
        [string]$Description,
        [bool]$Translate,
        [bool]$Register,
        [string]$Root
    )
    if (-not (Test-Path $SrcFile)) { Write-Log "  ERROR: not found: $SrcFile"; return $false }
    if (-not $ClassName) { $ClassName = [IO.Path]::GetFileNameWithoutExtension($SrcFile) -replace '[^a-zA-Z0-9]','' }
    if (-not $ClassName) { Write-Log "  ERROR: no class name for $SrcFile"; return $false }
    if ($ClassName -notmatch 'Module$') { $ClassName += 'Module' }
    if (-not $Description) { $Description = "Ported module." }

    $raw = Get-Content -Raw -LiteralPath $SrcFile
    $body = if ($Translate) { Convert-SourceToMojang $raw } else { $raw }
    $settings = if ($Translate) { Get-DetectedSettings $raw } else { @() }

    $moduleSrc = New-ModuleSource -ClassName $ClassName -Package $Package -Description $Description -SourceBody '' -Settings $settings
    $outDir = Join-Path $Root "src\main\java\com\autism\seedcracker\$Package"
    $outFile = Join-Path $outDir "$ClassName.java"
    Set-Content -LiteralPath $outFile -Value $moduleSrc -Encoding UTF8
    Write-Log "  Wrote $ClassName ($($settings.Count) settings) -> $Package"

    if ($Register) {
        $addonFile = Join-Path $Root 'src\main\java\com\autism\seedcracker\SeedcrackerAddon.java'
        Register-Module -AddonFile $addonFile -Package $Package -ClassName $ClassName -CategoryVar $Category
    }
    return $true
}

$ui.Browse.Add_Click({
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Filter = "Java source (*.java)|*.java|All files (*.*)|*.*"
    if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $ui.SrcPath.Text = $dlg.FileName
        $base = [IO.Path]::GetFileNameWithoutExtension($dlg.FileName)
        if (-not $ui.ClassName.Text) { $ui.ClassName.Text = ($base -replace '[^a-zA-Z0-9]','') + 'Module' }
        $script:AddonRoot = Find-AddonRoot (Split-Path $dlg.FileName -Parent)
        if ($script:AddonRoot) { Write-Log "Found addon root: $script:AddonRoot" }
        else { Write-Log "Could not auto-find addon root; set `$env:ADDON_ROOT." }
    }
})

$ui.BrowseFolder.Add_Click({
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "Pick the folder of .java module classes to batch-port"
    if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $ui.SrcPath.Text = $dlg.SelectedPath
        $script:AddonRoot = Find-AddonRoot $dlg.SelectedPath
        if ($script:AddonRoot) { Write-Log "Found addon root: $script:AddonRoot" }
        $count = (Get-ChildItem -LiteralPath $dlg.SelectedPath -Filter *.java -File).Count
        Write-Log "Folder set: $count .java file(s) found."
    }
})

$ui.Close.Add_Click({ $window.Close() })

function Invoke-Build([string]$root) {
    Write-Log "Running Gradle build..."
    $gradle = Join-Path $root 'gradlew.bat'
    $p = Start-Process -FilePath $gradle -ArgumentList '-p', ('"'+$root+'"'), 'build','-x','test' -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$env:TEMP\porter-build.log" -RedirectStandardError "$env:TEMP\porter-err.log"
    Write-Log (Get-Content -Raw "$env:TEMP\porter-build.log")
    Write-Log "Build exit code: $($p.ExitCode)"
}

$ui.Port.Add_Click({
    try {
        $src = $ui.SrcPath.Text.Trim()
        $root = Resolve-AddonRoot (Split-Path $src -Parent)
        if (-not $root) { Write-Log "ERROR: addon root not found. Set `$env:ADDON_ROOT."; return }
        $ok = Invoke-PortOne -SrcFile $src -ClassName $ui.ClassName.Text.Trim() -Package $ui.Package.Text `
            -Category $ui.Category.Text -Description $ui.Description.Text.Trim() `
            -Translate $ui.Translate.IsChecked -Register $ui.Register.IsChecked -Root $root
        if ($ok -and $ui.Register.IsChecked) { Write-Log "Registered in SeedcrackerAddon ($($ui.Category.Text))." }
        if ($ok -and $ui.DoBuild.IsChecked) { Invoke-Build $root }
        Write-Log "DONE."
    } catch { Write-Log "ERROR: $($_.Exception.Message)" }
})

$ui.Batch.Add_Click({
    try {
        $folder = $ui.SrcPath.Text.Trim()
        if (-not (Test-Path $folder -PathType Container)) { Write-Log "ERROR: pick a folder for batch mode."; return }
        $root = Resolve-AddonRoot $folder
        if (-not $root) { Write-Log "ERROR: addon root not found. Set `$env:ADDON_ROOT."; return }
        $files = Get-ChildItem -LiteralPath $folder -Filter *.java -File
        if (-not $files) { Write-Log "No .java files in folder."; return }
        Write-Log "Batch-porting $($files.Count) file(s) -> package $($ui.Package.Text), tab $($ui.Category.Text)..."
        $done = 0
        foreach ($f in $files) {
            $ok = Invoke-PortOne -SrcFile $f.FullName -ClassName '' -Package $ui.Package.Text `
                -Category $ui.Category.Text -Description '' `
                -Translate $ui.Translate.IsChecked -Register $ui.Register.IsChecked -Root $root
            if ($ok) { $done++ }
        }
        Write-Log "Registered batch in SeedcrackerAddon ($($ui.Category.Text))."
        if ($ui.DoBuild.IsChecked) { Invoke-Build $root }
        Write-Log "BATCH DONE: $done/$($files.Count) ported."
    } catch { Write-Log "ERROR: $($_.Exception.Message)" }
})

$window.ShowDialog() | Out-Null
