# getFromBrowseableDirectory

@@@ div { .group-scala }

## Signature

@@signature [FileAndResourceDirectives.scala](/http/src/main/scala/org/apache/pekko/http/scaladsl/server/directives/FileAndResourceDirectives.scala) { #getFromBrowseableDirectory }

@@@

## Description

The `getFromBrowseableDirectories` is a combination of serving files from the specified directories (like
`getFromDirectory`) and listing a browseable directory with `listDirectoryContents`.

Nesting this directive beneath `get` is not necessary as this directive will only respond to `GET` requests.

Use `getFromBrowseableDirectory` to serve only one directory.

Use `getFromDirectory` if directory browsing isn't required.


## Example

Scala
:   @@snip [FileAndResourceDirectivesExamplesSpec.scala](/docs/src/test/scala/docs/http/scaladsl/server/directives/FileAndResourceDirectivesExamplesSpec.scala) { #getFromBrowseableDirectory-examples }

Java
:   @@snip [FileAndResourceDirectivesExamplesTest.java](/docs/src/test/java/docs/http/javadsl/server/directives/FileAndResourceDirectivesExamplesTest.java) { #getFromBrowseableDirectory }

### Default file listing page example

Directives which list directories (e.g. `getFromBrowsableDirectory`) use an implicit `DirectoryRenderer`
instance to perform the actual rendering of the file listing. This rendered can be easily overridden by simply
providing one in-scope for the directives to use, so you can build your custom directory listings.

The default renderer is `org.apache.pekko.http.scaladsl.server.directives.FileAndResourceDirectives.defaultDirectoryRenderer`,
and renders a listing which looks like this:

![akka-http-file-listing.png](../../../akka-http-file-listing.png)
> 
Example page rendered by the `defaultDirectoryRenderer`.

It's possible to turn off rendering the footer stating which version of Apache Pekko HTTP is rendering this page by configuring
the `pekko.http.routing.render-vanity-footer` configuration option to `off`.