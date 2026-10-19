package agent.next.app.skill;

import java.util.Set;

/** JD-specific vocabulary, isolated from core runtime. */
public final class JdAppProfile {

    public static final AppProfile PROFILE = new AppProfile(
            "com.jingdong.app.mall",
            Set.of("搜索", "输入框", "搜索框"),
            Set.of("拍照", "大图", "显示模式", "筛选"),
            Set.of("搜索", "提交", "按钮"),
            Set.of("拍照", "大图", "显示模式", "筛选"),
            Set.of("商品", "商品卡", "卡片"),
            Set.of("广告", "地址", "筛选", "立即购买", "结算", "去结算"),
            Set.of("加入购物车"),
            Set.of("确定", "确认"),
            Set.of("购物车", "cart", "tab"),
            Set.of("结算", "购买", "广告"),
            Set.of("加入购物车", "商品详情", "立即购买"),
            "jd.cart");

    private JdAppProfile() {
    }
}
