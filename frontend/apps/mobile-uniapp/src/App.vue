<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
onLaunch(() => {
  // Stage5 extension: wechat login / pay adapters plug in here.
})
</script>

<style lang="scss">
/* 小程序 WXSS 不兼容 :root / html / body / *::before 等 Web 选择器，勿引入 tokens.css */
page {
  background: #f3f5f7;
  color: #17191c;
  font-size: 28rpx;
  font-family: 'PingFang SC', 'SF Pro Text', 'Helvetica Neue', 'Microsoft YaHei', sans-serif;
  box-sizing: border-box;
}

.page {
  min-height: 100vh;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.card {
  margin: 20rpx 24rpx;
  padding: 28rpx;
  background: #fff;
  border-radius: 24rpx;
  box-shadow: 0 8rpx 28rpx rgba(23, 25, 28, 0.04);
}

.btn-primary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 88rpx;
  margin-top: 24rpx;
  border-radius: 999rpx;
  background: #17191c;
  color: #fff;
  font-size: 30rpx;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.btn-primary.brand {
  background: #ff6a2b;
}

.btn-primary.disabled {
  opacity: 0.45;
}

.muted {
  color: #8b919a;
  font-size: 24rpx;
}

.warn {
  color: #c62828;
  font-size: 24rpx;
}

.ok {
  color: #1b7a4a;
  font-size: 24rpx;
}

.list-item {
  display: flex;
  padding: 20rpx 0;
  border-bottom: 1px solid #eef1f3;
}

.list-item .thumb {
  width: 140rpx;
  height: 140rpx;
  margin-right: 20rpx;
  border-radius: 16rpx;
  background: #e8ebef;
  flex-shrink: 0;
}

.empty {
  padding: 160rpx 48rpx;
  text-align: center;
  color: #8b919a;
  font-size: 28rpx;
  line-height: 1.6;
}
</style>
