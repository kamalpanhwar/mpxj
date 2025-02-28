# How To: Read MPX files
Versions of Microsoft Project up to Project 98 could read and write MPX files
as a data interchange format. Versions of Project after Project 98 until Project 2010
can only read MPX files. Versions of Microsoft Project after 2010 cannot read MPX files.
Other third party project planning applications continue to use MPX as a data interchange format.

## Reading MPX files
The simplest way to read an MPX file is to use the `UniversalProjectReader`:

```java
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.reader.UniversalProjectReader;

// ...

UniversalProjectReader reader = new UniversalProjectReader();
ProjectFile project = reader.read("my-sample.mpx");
```

## Using MPXReader

You can work directly with the `MPXReader` class by replacing `UniversalProjectReader`
with `MPXReader`. This provides access to additional options, as described below.

### Locale
It appears that very early in the life of the MPX file format, Microsoft Project was
internationalised to allow versions of the application to be used in languages other than English.
One unfortunate side effect of this was that the text used in the MPX file
format was also internationalised. Thus rather than having a single file format which could
be exchanged globally between any applications, you now need to know which internationalised
version of Microsoft Project was used to create the MPX file in order to read it
successfully.

Fortunately in most cases MPX files have been generated using the English language version
of Microsoft Project, or an application which generates this variant, so the default
settings for `MPXReader` will work.

If you encounter an MPX file  generated by something other than an English version of
Microsoft Project, you'll need to explicitly set the locale in order to read the file.
The sample below shows how this is done:


```java
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.mpx.MPXReader;

// ...

MPXReader reader = new MPXReader();
reader.setLocale(Locale.GERMAN);
ProjectFile project = reader.read("my-sample.mpx");
```

The following locales are supported by `MPXReader`:

* English
* German
* Spanish
* French
* Italian
* Portuguese
* Russian
* Swedish
* Chinese

You can retrieve a list of supported locales programmatically using the code shown below:

```java
import net.sf.mpxj.mpx.MPXReader;

// ...

Locale[] locales = MPXReader.getSupportedLocales();
```

### Ignore Text Models
You should not normally need to modify this option.

An MPX file consists of a series of sections with each section representing
a specific entity, for example tasks, resources, and so on. The set of
attributes written for each entity is not fixed, instead at the start of
each section the attributes which appear in the file are listed in two forms:
as a series of numeric values, and as a series on human-readable attribute names.

Originally MPXJ used to read both of these lists, however it was found that the
human-readable attribute names were often not consistent and caused problems
when attempting to read MPX files. The default now is that these attributes
are ignored. If for some reason you should wish to enable MPXJ's original
behaviour and read these files, you would call `setIgnoreTextModels` as
shown in the example below.

```java
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.mpx.MPXReader;

// ...

MPXReader reader = new MPXReader();
reader.setIgnoreTextModels(false);
ProjectFile project = reader.read("my-sample.mpx");
```
