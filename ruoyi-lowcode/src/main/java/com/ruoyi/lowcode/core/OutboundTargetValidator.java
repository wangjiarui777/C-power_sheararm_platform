package com.ruoyi.lowcode.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import org.springframework.stereotype.Component;

/** Resolves and rejects non-public connector destinations, including IPv4-mapped IPv6 addresses. */
@Component
public class OutboundTargetValidator
{
    public URI requirePublicHttps(String value)
    {
        try
        {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())))
                throw new IllegalArgumentException("HTTP连接器必须使用无凭据、无路径的固定 HTTPS 服务根地址");
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException("连接器域名无法解析");
            for (InetAddress address : addresses) requirePublic(address);
            return uri;
        }
        catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("HTTP连接器地址无法安全解析", ex); }
    }

    private void requirePublic(InetAddress address)
    {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())
            throw new IllegalArgumentException("HTTP连接器禁止访问本机、内网、链路本地或组播地址");
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4)
        {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            if ((first == 100 && second >= 64 && second <= 127) || first == 0 || first >= 224)
                throw new IllegalArgumentException("HTTP连接器目标不是公网单播地址");
        }
    }
}
