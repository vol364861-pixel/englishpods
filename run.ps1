$ErrorActionPreference = 'Stop'

# 读取项目配置
$envFile = Join-Path $PSScriptRoot '.env'

if (-not (Test-Path $envFile)) {
    throw "未找到 .env 配置文件！"
}

# 加载环境变量
foreach ($line in Get-Content -LiteralPath $envFile) {
    $line = $line.Trim()

    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
        continue
    }

    $index = $line.IndexOf('=')

    if ($index -lt 1) {
        continue
    }

    $name = $line.Substring(0, $index).Trim()
    $value = $line.Substring($index + 1).Trim()

    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

Write-Host "环境变量加载成功！" -ForegroundColor Green

# 启动 Java 项目
$jar = Join-Path $PSScriptRoot 'target\englishpods-0.0.1-SNAPSHOT.jar'

if (-not (Test-Path $jar)) {
    throw "未找到 JAR 文件，请先构建项目！"
}

java -jar $jar
