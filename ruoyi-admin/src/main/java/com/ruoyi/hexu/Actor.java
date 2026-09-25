package com.ruoyi.hexu;
/** Identity is established by authenticated server context, never request body fields. */
public final class Actor {
    public final long id; public final boolean platform; public final boolean staff;
    public Actor(long id,boolean platform,boolean staff){this.id=id;this.platform=platform;this.staff=staff;}
    public String key(){return (staff?"staff:":"member:")+id;}
}
