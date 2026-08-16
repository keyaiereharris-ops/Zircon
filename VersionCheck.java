import org.apache.maven.artifact.versioning.ComparableVersion;

public class VersionCheck {
    public static void main(String[] args) {
        ComparableVersion installed = new ComparableVersion("26.2.0.48-beta");
        ComparableVersion required = new ComparableVersion("26.1.2.10-beta");
        System.out.println("installed = " + installed);
        System.out.println("required  = " + required);
        System.out.println("installed.compareTo(required) = " + installed.compareTo(required));
        System.out.println("installed >= required ? " + (installed.compareTo(required) >= 0));
    }
}
