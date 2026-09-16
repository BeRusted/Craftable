package org.berusted.craftable.config;

/**
 * 服务端配置入口。
 *
 * <p>注意：本类位于主源集，不能引用任何可选模组（如 malilib）的类型，
 * 否则在未安装该模组的环境（包括 dedicated server）会触发 NoClassDefFoundError。
 */
public final class CraftableServerConfig {

    private CraftableServerConfig() {
    }

    public static EnvironmentScanSettings scanSettings() {
        return new EnvironmentScanSettings(
                8,
                4,
                5,
                true
        );
    }
}
