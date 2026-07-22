package com.westart.ai.westart.wechat.account;

import org.springframework.data.jpa.repository.JpaRepository;

interface WechatAccountRepository extends JpaRepository<WechatAccount, String> {
}
