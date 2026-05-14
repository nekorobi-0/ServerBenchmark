global.mspts = []
global.counter = 0
ServerEvents.tick(event => {
    global.counter += 1
    const mspt = event.server.getAverageTickTime()

    global.mspts.push({
        mspt: Number(mspt.toFixed(4))
    })

    if (global.counter<300) return

    JsonIO.write(
        'tps.json',
        {"data":global.mspts}
    )
    const $Minecraft = Java.loadClass("net.minecraft.client.Minecraft");
    const $CrashReport = Java.loadClass("net.minecraft.CrashReport");
    const $NullPointerException = Java.class.forName("java.lang.NullPointerException");
    $Minecraft.crash(new $CrashReport("You were unlucky :(", $NullPointerException.getConstructor().newInstance()));
})
