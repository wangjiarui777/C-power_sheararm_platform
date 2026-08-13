package com.ruoyi.system.security;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/** Strict allow-list sanitizer for administrator-authored notice HTML. */
@Component
public class NoticeHtmlSanitizer
{
    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "b", "em", "i", "u", "s", "blockquote",
                    "ul", "ol", "li", "h1", "h2", "h3", "h4", "pre", "code", "a", "img",
                    "table", "thead", "tbody", "tr", "th", "td", "span")
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .addProtocols("a", "href", "https")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")
            .preserveRelativeLinks(true);

    private final Cleaner cleaner = new Cleaner(SAFELIST);

    public String sanitize(String html)
    {
        if (html == null || html.isBlank())
        {
            return html;
        }
        Document dirty = Jsoup.parseBodyFragment(html);
        Document clean = cleaner.clean(dirty);
        clean.outputSettings().prettyPrint(false);
        for (Element image : clean.select("img[src]"))
        {
            if (!isAllowedImageSource(image.attr("src")))
            {
                image.remove();
            }
        }
        for (Element link : clean.select("a[target]"))
        {
            link.removeAttr("target");
        }
        return clean.body().html();
    }

    private boolean isAllowedImageSource(String source)
    {
        try
        {
            URI uri = URI.create(source);
            return uri.getScheme() == null && uri.getHost() == null
                    && source.matches("^/attachments/[A-Za-z0-9-]+/content$");
        }
        catch (IllegalArgumentException ignored)
        {
            return false;
        }
    }
}
