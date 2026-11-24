using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class DeviceAlarmReminders : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "alarm_reminders",
                schema: "identity",
                table: "devices",
                type: "boolean",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "alarm_reminders",
                schema: "identity",
                table: "devices");
        }
    }
}
