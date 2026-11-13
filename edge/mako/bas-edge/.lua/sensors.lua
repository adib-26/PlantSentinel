--[[
Sensor acquisition for the BAS edge node.

Replace sensors.read with real field-bus reads. Mako Server ships Modbus TCP and OPC UA clients, e.g.:

   local mb = require"modbus.client".connect("192.168.1.50")
   local regs = mb:rholding(0, 5)          -- read 5 holding registers
   return { temperature = regs[1] / 10, vibration = regs[2] / 100, rpm = regs[3],
            torque = regs[4] / 10, pressure = regs[5] / 100 }

Units expected by BAS Intelligence: °C, mm/s RMS, rpm, N·m, bar.
Until hardware is attached, this module simulates a healthy belt conveyor drive.
]]

local sensors = {}

local function noise(sd)
   -- Approximate Gaussian noise from the sum of uniforms.
   local s = 0
   for _ = 1, 6 do s = s + math.random() end
   return (s - 3) * sd
end

function sensors.read(machineCode)
   return {
      temperature = 40 * (1 + noise(0.015)),
      vibration = 1.2 * (1 + noise(0.05)),
      rpm = 1450 * (1 + noise(0.004)),
      torque = 25 * (1 + noise(0.03)),
      pressure = 5.0 * (1 + noise(0.012)),
   }
end

return sensors
