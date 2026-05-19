# Phone Camera

把闲置 Android 手机变成局域网网络摄像头。

## 运行效果

<table>
  <tr>
    <td align="center">
      <strong>手机端效果</strong>
    </td>
    <td align="center">
      <strong>浏览器打开视频效果</strong>
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <img src="pc_side.jpg" alt="手机端显示网络摄像头地址和连接信息" width="360">
    </td>
    <td align="center" width="50%">
      <img src="phone_side.jpg" alt="浏览器中打开手机摄像头视频画面" width="360">
    </td>
  </tr>
</table>

## 兼容性

- 最低 Android 版本：Android 4.1，API 16。
- 摄像头实现：使用旧版 `android.hardware.Camera`，尽量兼容老手机。
- HTTP 视频流：MJPEG，可在大多数桌面浏览器和 VLC 中查看。
- RTSP 音视频流：H.264 视频 + AAC 音频，适合 VLC、ffmpeg 等客户端。
- 访问控制：HTTP Basic Auth，可在手机端设置用户名和密码。

## 功能

- 使用手机摄像头采集视频画面。
- 支持前后摄像头切换。
- 支持摄像头缩放，设备支持时会显示缩放滑条。
- 手机端显示浏览器控制页地址，例如 `http://192.168.1.23:8080/`。
- 手机端显示 MJPEG 原始视频流地址，例如 `http://192.168.1.23:8080/stream.mjpg`。
- 手机端显示 RTSP 音视频地址，例如 `rtsp://admin:123456@192.168.1.23:8554/camera`。
- 浏览器控制页支持切换摄像头、调整缩放、切换输出横屏/竖屏。
- MJPEG 和 RTSP 视频都支持横屏/竖屏输出切换。
- 手机端显示当前连接用户列表和连接历史。
- 手机端可设置连接用户名和密码。
- 内置关于弹窗，显示版本、作者、邮箱、捐助说明和二维码。
- 捐助二维码支持长按保存。
- 运行时保持屏幕常亮。

默认登录信息：

- 用户名：`admin`
- 密码：`123456`

## 使用方法

1. 用 Android Studio 打开本项目。
2. 构建并安装到 Android 手机上。
3. 按提示授予摄像头和麦克风权限。
4. 让手机和观看设备连接到同一个 Wi-Fi 或热点。
5. 打开手机端显示的浏览器地址，并输入用户名和密码登录。
6. 如需 RTSP，可用 VLC 或 ffmpeg 打开手机端显示的 RTSP 地址。

## 构建

在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat assembleDebug
```

也可以使用 Android Studio 的 Build APK 功能。

## 作者

- 作者：Pyrrhus
- 邮箱：zhangxuefeng@batonsoft.com

## 捐助说明

本软件永久免费开源，无广告、无捆绑、无功能限制。

若您觉得软件好用，日常使用带来便利，可自愿小额捐助支持作者持续更新维护。

捐助完全自愿，不捐助不影响任何使用权限，所有功能永久免费开放。

感谢每一份善意与支持！本捐助为用户自愿善意赞助，不属于商品交易、付费服务，不对应任何商品及权益，仅用于支持开发者日常开发维护。
