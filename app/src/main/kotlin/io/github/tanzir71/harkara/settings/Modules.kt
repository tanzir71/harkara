package io.github.tanzir71.harkara.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.tanzir71.harkara.R

/**
 * Optional modules from Settings → Modules (handover §4.1). [defaultOn] is the shipped default;
 * "when used" modules default on because they only wake up while their screen is open.
 * [ready] is false until the milestone that implements the module lands; the switch still persists
 * but the row is labelled "Coming soon". [greyArea] modules must stay off by default.
 */
enum class Module(
    val key: String,
    val defaultOn: Boolean,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @DrawableRes val icon: Int,
    val ready: Boolean = false,
    val greyArea: Boolean = false,
) {
    OFFLINE_MAP("offline_map", false, R.string.module_offline_map, R.string.module_offline_map_sub, R.drawable.ic_map),
    NO_MAP_LITE("no_map_lite", false, R.string.module_no_map_lite, R.string.module_no_map_lite_sub, R.drawable.ic_list),
    SAVED_PINS("saved_pins", true, R.string.module_saved_pins, R.string.module_saved_pins_sub, R.drawable.ic_layers),
    LOCATION_CARD("location_card", true, R.string.module_location_card, R.string.module_location_card_sub, R.drawable.ic_pin),
    LIVE_SHARE("live_share", false, R.string.module_live_share, R.string.module_live_share_sub, R.drawable.ic_locate),
    MEETUP_ARROW("meetup_arrow", true, R.string.module_meetup, R.string.module_meetup_sub, R.drawable.ic_bearing),
    CHECK_INS("check_ins", true, R.string.module_check_ins, R.string.module_check_ins_sub, R.drawable.ic_check),
    TRIP_PLAN("trip_plan", false, R.string.module_trip_plan, R.string.module_trip_plan_sub, R.drawable.ic_queued),
    COMPASS("compass", true, R.string.module_compass, R.string.module_compass_sub, R.drawable.ic_compass),
    QIBLA_PRAYER("qibla_prayer", false, R.string.module_qibla, R.string.module_qibla_sub, R.drawable.ic_qibla),
    TORCH_TOOLS("torch_tools", true, R.string.module_torch, R.string.module_torch_sub, R.drawable.ic_flashlight),
    QR("qr", true, R.string.module_qr, R.string.module_qr_sub, R.drawable.ic_qr),
    HANDBOOK("handbook", true, R.string.module_handbook, R.string.module_handbook_sub, R.drawable.ic_handbook),
    MESH("mesh", false, R.string.module_mesh, R.string.module_mesh_sub, R.drawable.ic_mesh),
    NODE("node", false, R.string.module_node, R.string.module_node_sub, R.drawable.ic_node),
    PUSH_TO_TALK("push_to_talk", false, R.string.module_ptt, R.string.module_ptt_sub, R.drawable.ic_mic),
    RADAR("radar", false, R.string.module_radar, R.string.module_radar_sub, R.drawable.ic_radar),
    FILE_DROP("file_drop", false, R.string.module_file_drop, R.string.module_file_drop_sub, R.drawable.ic_drop),
    LITE_MODE("lite_mode", false, R.string.module_lite, R.string.module_lite_sub, R.drawable.ic_layers),
    SMS_DIRECT("sms_direct", false, R.string.module_sms_direct, R.string.module_sms_direct_sub, R.drawable.ic_sms, greyArea = true),
    SMS_GATEWAY("sms_gateway", false, R.string.module_sms_gateway, R.string.module_sms_gateway_sub, R.drawable.ic_relay, greyArea = true),
}
