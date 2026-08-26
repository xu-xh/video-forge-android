# video-forge-android 远端 APK 验收指引（请在有 adb 的本机执行）

1) 取得远端 APK（仅此来源）
scp -i /Users/xuxiaohao/Desktop/xuxh/personal/.ssh/xuxh-personal-workspace.pem -F /Users/xuxiaohao/Desktop/xuxh/personal/.ssh/config root@47.97.27.108:/root/workspace/video-forge-android/app/build/outputs/apk/debug/app-debug.apk /tmp/app-debug.apk

2) 安装到手机
adb install -r /tmp/app-debug.apk

3) 启动应用
adb shell am start -n com.xuxh.videoforge/.MainActivity

4) 简单验收（恢复能力）
- 提交一条任务（非 error prompt）
- 观察状态列表是否走到 PROCESSING
- 重开应用后确认任务仍存在，并继续推进到 DONE/FAILED

5) 关键远端审计
- 审计日志: /root/workspace/video-forge-android/.verify/verify_remote_debug_apk.log
- 生成脚本: /root/workspace/video-forge-android/scripts/verify_remote_debug_apk.sh

注意：远端机器未内置 adb，因此无法在远端直接安装/运行。
