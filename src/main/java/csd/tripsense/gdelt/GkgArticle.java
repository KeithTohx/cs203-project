package csd.tripsense.gdelt;

import java.time.LocalDateTime;
import java.util.Set;

/** GkgArticle: a record holding url, title, description, domain, publishedAt and countryCodes */
public record GkgArticle(String url, String title, String description, String domain,
                         LocalDateTime publishedAt, Set<String> countryCodes) {

}
