package io.linguarobot.aws.cdk.maven;

import java.util.Objects;

/**
 * A reference to a template. It can be either template url or template body.
 */
public class TemplateRef {

    private final String url;
    private final String body;

    private TemplateRef(String url, String body) {
        this.url = url;
        this.body = body;
    }

    public String getUrl() {
        return url;
    }

    public String getBody() {
        return body;
    }

    public static TemplateRef fromUrl(String url) {
        return new TemplateRef(url, null);
    }

    public static TemplateRef fromString(String templateBody) {
        return new TemplateRef(null, templateBody);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TemplateRef that = (TemplateRef) o;
        return Objects.equals(url, that.url) &&
                Objects.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, body);
    }

    @Override
    public String toString() {
        return url != null ? "fromUrl(\"" + url + "\")" : "fromString(...)";
    }
}
