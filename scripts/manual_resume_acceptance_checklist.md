# video-forge-android 恢复能力手工验收清单

> 目标：验证 APP 在重启后是否能从未完成任务恢复轮询并最终到终态。

## 前提
- APK 文件：/root/workspace/video-forge-android/app/build/outputs/apk/debug/app-debug.apk
- 本次验证请在有 adb 的本机执行（远端 ECS 无 adb）

## 步骤
1. 拉取 APK 到本机
   - scp -i /Users/xuxiaohao/Desktop/xuxh/personal/.ssh/xuxh-personal-workspace.pem -F /Users/xuxiaohao/Desktop/xuxh/personal/.ssh/config root@47.97.27.108:/root/workspace/video-forge-android/app/build/outputs/apk/debug/app-debug.apk /tmp/app-debug.apk
2. 安装与启动
   - adb install -r /tmp/app-debug.apk
   - adb shell am start -n com.xuxh.videoforge/.MainActivity
3. 提交任务（普通文本）
   - 输入任务示例： verify resume test
   - 记录观察：列表出现任务且至少能看到以下任一状态：SUBMITTED / QUEUED / PROCESSING
4. 触发重启场景
   - 立即终止应用（系统设置 -> 应用 -> 强制停止）
   - 或杀进程：adb shell am force-stop com.xuxh.videoforge
5. 重启应用
   - adb shell am start -n com.xuxh.videoforge/.MainActivity
6. 验证恢复
   - 列表应仍存在任务项（未消失）
   - 任务应自动恢复轮询并继续变更状态
   - 最终到达 DONE 或 FAILED（均可，视 prompt）

## 判定规则
- PASS：任务未丢失，重启后继续推进状态，最终可达终态
- FAIL：任务消失、状态停在中间态不再变化、或无法再进入 DONE 或 FAILED

## 关联证据（远端）
- 构建脚本：/root/workspace/video-forge-android/scripts/verify_remote_debug_apk.sh
- 日志：/root/workspace/video-forge-android/.verify/verify_remote_debug_apk.log
