package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体类
 *
 * @author 李
 * @version 1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
//chain = true,则对应字段的setter方法调用后，会返回当前对象
@Accessors(chain = true)
@TableName("tb_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;
    //用户id（主键）
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    //手机号码
    private String phone;
    //密码
    private String password;
    //昵称
    private String nickName;
    //用户头像
    private String icon = "";
    //创建时间
    private LocalDateTime createTime;
    //更新时间
    private LocalDateTime updateTime;
}
