package com.sslmonitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 添加/更新域名的请求体。host 允许用户输入带协议/端口/路径，由后端清洗。
 */
public class DomainRequest {

    @NotBlank(message = "域名不能为空")
    @Size(max = 300)
    private String host;

    private Integer port = 443;

    @Size(max = 200)
    private String remark = "";

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
