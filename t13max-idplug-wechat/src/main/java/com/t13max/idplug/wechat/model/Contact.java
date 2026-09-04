package com.t13max.idplug.wechat.model;

/** 保存联系人昵称和备注，身份始终使用微信标识而不是显示名称。 */
public record Contact(String id, String nickname, String remark) {
    /** 优先使用备注，其次使用昵称，缺失时显示联系人标识。 */
    public String displayName() {
        return remark != null && !remark.isBlank() ? remark : nickname != null && !nickname.isBlank() ? nickname : id;
    }
}
