package es.myfamilytube.ui;

/** An approved channel shown as a big button on a kid's home. */
final class FamilyChannelItem {
    final String channelId;
    final String name;

    FamilyChannelItem(String channelId, String name) {
        this.channelId = channelId;
        this.name = name;
    }
}
